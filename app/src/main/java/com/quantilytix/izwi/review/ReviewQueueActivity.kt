package com.quantilytix.izwi.review

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ReviewStatus
import com.quantilytix.izwi.databinding.ActivityReviewQueueBinding
import com.quantilytix.izwi.session.SessionManager
import com.quantilytix.izwi.sync.SyncStatusActivity
import kotlinx.coroutines.launch

class ReviewQueueActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context) = Intent(context, ReviewQueueActivity::class.java)
    }

    private lateinit var binding: ActivityReviewQueueBinding
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as IzwiApplication
        val sessionId = SessionManager.sessionId(this)

        val adapter = ClipAdapter(
            onPlay = ::playClip,
            onEditTranscript = { clip -> editTranscript(clip) },
            onToggleStatus = { clip -> toggleStatus(clip) },
        )
        binding.clipsRecycler.layoutManager = LinearLayoutManager(this)
        binding.clipsRecycler.adapter = adapter

        lifecycleScope.launch {
            app.database.clipDao().observeForSession(sessionId).collect { clips ->
                adapter.submit(clips)
                updateSummary(clips)
            }
        }

        binding.proceedToSyncButton.setOnClickListener {
            startActivity(SyncStatusActivity.intent(this))
        }
    }

    private fun updateSummary(clips: List<ClipEntity>) {
        val accepted = clips.count { it.reviewStatus == ReviewStatus.ACCEPTED }
        val warning = clips.count { it.reviewStatus == ReviewStatus.WARNING }
        val retake = clips.count { it.reviewStatus == ReviewStatus.RETAKE }
        val skipped = clips.count { it.reviewStatus == ReviewStatus.SKIPPED }
        val totalMinutes = clips.sumOf { it.durationSeconds } / 60.0
        binding.summaryText.text = String.format(
            "Accepted %d · Warning %d · Retake %d · Skipped %d — %.1f min",
            accepted, warning, retake, skipped, totalMinutes,
        )
    }

    private fun playClip(clip: ClipEntity) {
        val path = clip.filePath ?: return
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            prepare()
            start()
        }
    }

    private fun editTranscript(clip: ClipEntity) {
        val input = EditText(this).apply { setText(clip.transcript) }
        AlertDialog.Builder(this)
            .setTitle("Correct transcript")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val app = application as IzwiApplication
                lifecycleScope.launch {
                    app.database.clipDao().update(clip.copy(transcript = input.text.toString()))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleStatus(clip: ClipEntity) {
        val newStatus = if (clip.reviewStatus == ReviewStatus.RETAKE) ReviewStatus.WARNING else ReviewStatus.RETAKE
        val app = application as IzwiApplication
        lifecycleScope.launch {
            app.database.clipDao().update(clip.copy(reviewStatus = newStatus))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
