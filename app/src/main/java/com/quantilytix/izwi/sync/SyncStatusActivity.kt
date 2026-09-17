package com.quantilytix.izwi.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.data.BatchEntity
import com.quantilytix.izwi.data.ConsentRecordEntity
import com.quantilytix.izwi.data.UploadStatus
import com.quantilytix.izwi.databinding.ActivitySyncStatusBinding
import com.quantilytix.izwi.export.BatchChunker
import com.quantilytix.izwi.export.BatchExporter

import com.quantilytix.izwi.export.UploadWorker
import com.quantilytix.izwi.session.SessionManager
import kotlinx.coroutines.launch

class SyncStatusActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context) = Intent(context, SyncStatusActivity::class.java)
    }

    private lateinit var binding: ActivitySyncStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun syncNow() {
        val app = application as IzwiApplication
        val sessionId = SessionManager.sessionId(this)

        lifecycleScope.launch {
            val consent: ConsentRecordEntity? = app.database.consentDao().getForSession(sessionId)
            if (consent == null) {
                binding.queuedText.text = "Missing consent record for this session — cannot export"
                return@launch
            }

            val unbatched = app.database.clipDao().getUnbatchedApproved(sessionId)
            if (unbatched.isEmpty()) {
                refreshQueuedCount()
                return@launch
            }

            val chunks = BatchChunker.chunkByDuration(unbatched)
            for (chunk in chunks) {
                val zipFile = BatchExporter.buildBatchZip(this@SyncStatusActivity, chunk, consent)
                val batchId = zipFile.nameWithoutExtension
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
                app.database.clipDao().assignBatch(chunk.map { it.clipId }, batchId, UploadStatus.QUEUED)
                enqueueUpload(batchId)
            }
            refreshQueuedCount()
        }
    }

    private fun enqueueUpload(batchId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadWorker.KEY_BATCH_ID, batchId).build())
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }
}
