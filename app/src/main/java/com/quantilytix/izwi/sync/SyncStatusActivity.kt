package com.quantilytix.izwi.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.withTransaction
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.data.BatchEntity
import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ConsentRecordEntity
import com.quantilytix.izwi.data.UploadStatus
import com.quantilytix.izwi.databinding.ActivitySyncStatusBinding
import com.quantilytix.izwi.export.BatchChunker
import com.quantilytix.izwi.export.BatchExporter

import com.quantilytix.izwi.export.UploadWorker
import com.quantilytix.izwi.session.SessionManager
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.launch

class SyncStatusActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context) = Intent(context, SyncStatusActivity::class.java)
    }

    private lateinit var binding: ActivitySyncStatusBinding
    private var isSyncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.headerContainer.applySystemBarInsetPadding(applyTop = true)
        binding.batchesRecycler.applySystemBarInsetPadding(applyBottom = true)

        val app = application as IzwiApplication
        val sessionId = SessionManager.sessionId(this)

        val adapter = BatchAdapter(onRetry = { batch -> enqueueUpload(batch.batchId) })
        binding.batchesRecycler.layoutManager = LinearLayoutManager(this)
        binding.batchesRecycler.adapter = adapter

        lifecycleScope.launch {
            app.database.batchDao().observeForSession(sessionId).collect { batches ->
                adapter.submit(batches)
                updateSummary(batches)
            }
        }

        refreshQueuedCount()

        binding.syncNowButton.setOnClickListener { syncNow() }
    }

    private fun refreshQueuedCount() {
        val app = application as IzwiApplication
        val sessionId = SessionManager.sessionId(this)
        lifecycleScope.launch {
            val unbatched = app.database.clipDao().getUnbatchedApproved(sessionId)
            binding.queuedText.text = "${unbatched.size} accepted clips not yet uploaded"
        }
    }

    private fun updateSummary(batches: List<BatchEntity>) {
        val lastSync = batches
            .filter { it.uploadStatus == UploadStatus.UPLOADED }
            .maxByOrNull { it.lastAttemptAtUtc ?: "" }
            ?.lastAttemptAtUtc
        binding.lastSyncText.text = "Last successful sync: ${lastSync ?: "never"}"
    }

    /**
     * Claiming and exporting used to be two separate steps: read the
     * unbatched clips, then later mark them batched. A double tap on Sync
     * now (no debounce existed) let two coroutines both read the same
     * "unbatched" clips before either had marked them claimed, producing
     * two identical batches, two identical uploads, two PRs with the same
     * 43 clips (Audry's session 2026-09-22, PRs #5/#6 — confirmed byte-
     * identical clip_ids, 11 seconds apart). Fixed two ways: the button is
     * disabled for the duration of a sync, and the read-then-claim step now
     * happens inside one DB transaction, so even a genuinely concurrent
     * call can't claim clips the other has already taken.
     */
    private fun syncNow() {
        if (isSyncing) return
        isSyncing = true
        binding.syncNowButton.isEnabled = false

        val app = application as IzwiApplication
        val sessionId = SessionManager.sessionId(this)

        lifecycleScope.launch {
            try {
                val consent: ConsentRecordEntity? = app.database.consentDao().getForSession(sessionId)
                if (consent == null) {
                    binding.queuedText.text = "Missing consent record for this session — cannot export"
                    return@launch
                }

                val claimedBatches: List<Pair<String, List<ClipEntity>>> = app.database.withTransaction {
                    val unbatched = app.database.clipDao().getUnbatchedApproved(sessionId)
                    if (unbatched.isEmpty()) {
                        emptyList()
                    } else {
                        // One language per batch, then bounded by duration.
                        BatchChunker.chunkByLanguageAndDuration(unbatched).map { chunk ->
                            val batchId = BatchExporter.newBatchId()
                            app.database.clipDao().assignBatch(chunk.map { it.clipId }, batchId, UploadStatus.QUEUED)
                            batchId to chunk
                        }
                    }
                }

                for ((batchId, chunk) in claimedBatches) {
                    val zipFile = BatchExporter.buildBatchZip(this@SyncStatusActivity, chunk, consent, batchId)
                    val batch = BatchEntity(
                        batchId = batchId,
                        sessionId = sessionId,
                        speakerId = consent.speakerId,
                        createdAtUtc = SessionManager.nowUtcIso(),
                        clipCount = chunk.size,
                        zipPath = zipFile.absolutePath,
                        uploadStatus = UploadStatus.PENDING,
                    )
                    app.database.batchDao().upsert(batch)
                    enqueueUpload(batchId)
                }
                refreshQueuedCount()
            } finally {
                isSyncing = false
                binding.syncNowButton.isEnabled = true
            }
        }
    }

    /**
     * Used by Sync now and by the Retry button on a failed batch. Unique work
     * keyed by batch id with KEEP means a batch already queued, running or
     * waiting out a retry backoff is never uploaded a second time in
     * parallel; a finished (failed) attempt is replaced, so Retry still
     * works once the earlier attempt has ended.
     */
    private fun enqueueUpload(batchId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadWorker.KEY_BATCH_ID, batchId).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("upload_$batchId", ExistingWorkPolicy.KEEP, request)
    }
}
