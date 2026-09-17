package com.quantilytix.izwi.recording

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ReviewStatus
import com.quantilytix.izwi.databinding.ActivityRecordingBinding
import com.quantilytix.izwi.quality.QualityAnalyzer
import com.quantilytix.izwi.quality.QualityResult
import com.quantilytix.izwi.review.ReviewQueueActivity
import com.quantilytix.izwi.session.Prompt
import com.quantilytix.izwi.session.ScriptRepository
import com.quantilytix.izwi.session.SessionManager
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class RecordingActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context) = Intent(context, RecordingActivity::class.java)
    }

    private lateinit var binding: ActivityRecordingBinding
    private lateinit var repository: ScriptRepository
    private lateinit var prompts: MutableList<Prompt>
    private var index = 0

    private val recorder = WavRecorder()
    private var currentWavFile: File? = null
    private var currentClipId: String? = null
    private var pendingQuality: QualityResult? = null
    private var isRecording = false

    private var mediaPlayer: MediaPlayer? = null
    private val elapsedHandler = Handler(Looper.getMainLooper())
    private var elapsedStartMs = 0L
    private val elapsedRunnable = object : Runnable {
        override fun run() {
            val seconds = (System.currentTimeMillis() - elapsedStartMs) / 1000.0
            binding.elapsedTimeText.text = String.format("%.1fs", seconds)
            elapsedHandler.postDelayed(this, 100)
        }
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Microphone permission is required to record", Toast.LENGTH_LONG).show()
        }
    }

    private val speakerId get() = SessionManager.speakerId(this)
    private val sessionId get() = SessionManager.sessionId(this)
    private val consentVersion get() = SessionManager.consentVersion(this)
    private val scriptVersion get() = SessionManager.scriptVersion(this)
    private val deviceModel get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetPadding(applyTop = true, applyBottom = true)

        repository = ScriptRepository(this)
        prompts = repository.loadActive().toMutableList()
        index = SessionManager.promptIndex(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        showCurrentPrompt()
        wireButtons()
    }

    private fun wireButtons() {
        binding.recordButton.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        binding.replayButton.setOnClickListener { replay() }
        binding.retakeButton.setOnClickListener { retake() }
        binding.skipButton.setOnClickListener { promptSkipReason() }
        binding.acceptButton.setOnClickListener { acceptAndNext() }
        binding.finishSessionButton.setOnClickListener { goToReview() }
        binding.editPromptButton.setOnClickListener { editCurrentPrompt() }
        binding.scriptProgressButton.setOnClickListener {
            startActivity(com.quantilytix.izwi.session.ScriptEditorActivity.intent(this, speakerId))
        }
    }

    /**
     * Fixes wording (grammar, formality) on the upcoming prompt before it's
     * read aloud. Only available while no take is in progress — editing a
     * prompt after recording it belongs in the review queue, where changing
     * the transcript to no longer match the spoken audio forces a retake.
     */
    private fun editCurrentPrompt() {
        val p = prompts[index]
        val input = EditText(this).apply { setText(p.text) }
        AlertDialog.Builder(this)
            .setTitle("Edit prompt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    prompts[index] = p.copy(text = text)
                    repository.saveActive(prompts, scriptVersion)
                    binding.promptText.text = text
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCurrentPrompt() {
        if (index >= prompts.size) {
            goToReview()
            return
        }
        val p = prompts[index]
        binding.progressText.text = "${index + 1} / ${prompts.size}"
        binding.categoryText.text = p.category.replace('_', ' ')
        binding.promptText.text = p.text
        binding.elapsedTimeText.text = "0.0s"
        binding.warningText.visibility = android.view.View.GONE
        binding.levelMeter.clear()
        resetTakeState()
    }

    private fun resetTakeState() {
        currentWavFile = null
        currentClipId = null
        pendingQuality = null
        binding.recordButton.isEnabled = true
        binding.recordButton.text = getString(com.quantilytix.izwi.R.string.recording_record)
        binding.replayButton.isEnabled = false
        binding.retakeButton.isEnabled = false
        binding.acceptButton.isEnabled = false
        binding.skipButton.isEnabled = true
        binding.editPromptButton.isEnabled = true
        binding.warningText.visibility = android.view.View.GONE
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val clipId = "clip_${UUID.randomUUID().toString().take(12)}"
        currentClipId = clipId
        val dir = File(filesDir, "audio/$speakerId/$sessionId").apply { mkdirs() }
        val file = File(dir, "$clipId.wav")
        currentWavFile = file

        recorder.onLevel = { level -> runOnUiThread { binding.levelMeter.pushLevel(level) } }
        recorder.start(file)

        isRecording = true
        binding.recordButton.text = getString(com.quantilytix.izwi.R.string.recording_stop)
        binding.skipButton.isEnabled = false
        binding.editPromptButton.isEnabled = false
        elapsedStartMs = System.currentTimeMillis()
        elapsedHandler.post(elapsedRunnable)
    }

    private fun stopRecording() {
        val file = recorder.stop()
        isRecording = false
        elapsedHandler.removeCallbacks(elapsedRunnable)
        binding.recordButton.text = getString(com.quantilytix.izwi.R.string.recording_record)
        binding.recordButton.isEnabled = true

        if (file == null || !file.exists()) {
            Toast.makeText(this, "Recording failed, try again", Toast.LENGTH_SHORT).show()
            resetTakeState()
            return
        }

        binding.replayButton.isEnabled = true
        binding.retakeButton.isEnabled = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { QualityAnalyzer.analyze(file) }
            pendingQuality = result
            binding.acceptButton.isEnabled = true
            if (result.warnings.isNotEmpty()) {
                binding.warningText.visibility = android.view.View.VISIBLE
                binding.warningText.text = result.warnings.joinToString(" · ")
            } else {
                binding.warningText.visibility = android.view.View.GONE
            }
        }
    }

    private fun replay() {
        val file = currentWavFile ?: return
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }

    private fun retake() {
        currentWavFile?.delete()
        binding.levelMeter.clear()
        resetTakeState()
    }

    private fun promptSkipReason() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Why skip this prompt?")
            .setView(input)
            .setPositiveButton("Skip") { _, _ ->
                val reason = input.text.toString().ifBlank { "no reason given" }
                saveSkippedClip(reason)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSkippedClip(reason: String) {
        val p = prompts[index]
        val clip = ClipEntity(
            clipId = "skip_${UUID.randomUUID().toString().take(12)}",
            speakerId = speakerId,
            sessionId = sessionId,
            promptId = p.id,
            category = p.category,
            language = "sn",
            transcript = p.text,
            sampleRate = 0,
            durationSeconds = 0.0,
            qualityScore = 0.0,
            snrProxy = 0.0,
            speechRatio = 0.0,
            clippingDetected = false,
            recordedAtUtc = SessionManager.nowUtcIso(),
            deviceModel = deviceModel,
            consentVersion = consentVersion,
            scriptVersion = scriptVersion,
            reviewStatus = ReviewStatus.SKIPPED,
            skipReason = reason,
            filePath = null,
        )
        persistAndAdvance(clip)
    }

    private fun acceptAndNext() {
        val quality = pendingQuality ?: return
        val file = currentWavFile ?: return
        val clipId = currentClipId ?: return
        val p = prompts[index]

        fun save(status: String) {
            val clip = ClipEntity(
                clipId = clipId,
                speakerId = speakerId,
                sessionId = sessionId,
                promptId = p.id,
                category = p.category,
                language = "sn",
                transcript = p.text,
                sampleRate = recorder.sampleRate,
                durationSeconds = quality.durationSeconds,
                qualityScore = quality.qualityScore,
                snrProxy = quality.snrProxy,
                speechRatio = quality.speechRatio,
                clippingDetected = quality.clippingDetected,
                recordedAtUtc = SessionManager.nowUtcIso(),
                deviceModel = deviceModel,
                consentVersion = consentVersion,
                scriptVersion = scriptVersion,
                reviewStatus = status,
                filePath = file.absolutePath,
            )
            persistAndAdvance(clip)
        }

        if (quality.suggestedStatus == ReviewStatus.RETAKE) {
            AlertDialog.Builder(this)
                .setTitle("Quality suggests a retake")
                .setMessage(quality.warnings.joinToString("\n"))
                .setPositiveButton("Accept anyway") { _, _ -> save(ReviewStatus.WARNING) }
                .setNegativeButton("Retake") { _, _ -> retake() }
                .show()
        } else {
            save(quality.suggestedStatus)
        }
    }

    private fun persistAndAdvance(clip: ClipEntity) {
        val app = application as IzwiApplication
        lifecycleScope.launch {
            app.database.clipDao().upsert(clip)
            index += 1
            SessionManager.setPromptIndex(this@RecordingActivity, index)
            showCurrentPrompt()
        }
    }

    private fun goToReview() {
        startActivity(ReviewQueueActivity.intent(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        elapsedHandler.removeCallbacks(elapsedRunnable)
        mediaPlayer?.release()
        if (isRecording) recorder.cancel()
    }
}
