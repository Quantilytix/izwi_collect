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
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
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
        binding.headerContainer.applySystemBarInsetPadding(applyTop = true)
        binding.proceedToSyncButton.applySystemBarInsetPadding(applyBottom = true)

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

        val speakerId = SessionManager.speakerId(this)
        lifecycleScope.launch {
            app.database.clipDao().observeTotalDurationForSpeaker(speakerId).collect { totalSeconds ->
                updateOverallProgress(totalSeconds / 60.0)
            }
        }

        binding.scriptProgressLink.setOnClickListener {
            startActivity(com.quantilytix.izwi.session.ScriptEditorActivity.intent(this, speakerId))
        }

        binding.proceedToSyncButton.setOnClickListener {
            startActivity(SyncStatusActivity.intent(this))
        }
    }

    private fun updateOverallProgress(totalMinutes: Double) {
        val baselineMinutes = 120.0
        val scaleMinutes = 300.0
        binding.overallProgressMeter.baselineMinutes = baselineMinutes
        binding.overallProgressMeter.scaleMinutes = scaleMinutes
        binding.overallProgressMeter.progressMinutes = totalMinutes

        binding.overallProgressText.text = if (totalMinutes < baselineMinutes) {
            String.format(
                "Overall for this speaker: %.0f / %.0f min — %.0f min to the 2h baseline",
                totalMinutes, scaleMinutes, baselineMinutes - totalMinutes,
            )
        } else {
            String.format(
                "Overall for this speaker: %.0f / %.0f min — 2h baseline reached, keep going",
                totalMinutes, scaleMinutes,
            )
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

    /**
     * Fixing a prompt's wording belongs on the recording screen, before it's
     * spoken. Editing a transcript here, after audio already exists, means
     * the text no longer certainly matches what was recorded — so it always
     * forces a retake rather than silently keeping an accepted/warning
     * status against changed text. Skipped clips have no audio to mismatch,
     * so their transcript can be corrected freely.
     */
    private fun editTranscript(clip: ClipEntity) {
        val input = EditText(this).apply { setText(clip.transcript) }
        AlertDialog.Builder(this)
            .setTitle("Correct transcript")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTranscript = input.text.toString()
                val forcesRetake = clip.filePath != null && clip.reviewStatus != ReviewStatus.RETAKE
                val app = application as IzwiApplication
                lifecycleScope.launch {
                    app.database.clipDao().update(
                        clip.copy(
                            transcript = newTranscript,
                            reviewStatus = if (clip.filePath != null) ReviewStatus.RETAKE else clip.reviewStatus,
                        )
                    )
                    if (forcesRetake) {
                        android.widget.Toast.makeText(
                            this@ReviewQueueActivity,
                            "Transcript changed — marked for retake so audio and text match",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
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
