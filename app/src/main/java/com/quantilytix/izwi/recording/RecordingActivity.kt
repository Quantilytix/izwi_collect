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
import android.view.WindowManager
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
        private const val EXTRA_CATEGORY = "category_filter"
        private const val EXTRA_RETAKE_PROMPT_ID = "retake_prompt_id"
        private const val EXTRA_RETAKE_CATEGORY = "retake_category"
        private const val EXTRA_RETAKE_TEXT = "retake_text"

        // Free-form takes are flagged past 180s; stopping a forgotten take at
        // 300s keeps the file small enough for QualityAnalyzer to load whole.
        private const val MAX_TAKE_SECONDS = 300.0

        /** [categoryFilter] narrows the session to just that category's
         * prompts — e.g. "go record all the stock_inventory clips" — with
         * its own resume position, independent of the whole-script order. */
        fun intent(context: Context, categoryFilter: String? = null) =
            Intent(context, RecordingActivity::class.java).apply {
                if (!categoryFilter.isNullOrBlank()) putExtra(EXTRA_CATEGORY, categoryFilter)
            }

        /** Opens the recorder on one prompt only, for a clip marked retake in
         * the review queue. [text] is the clip's transcript, so a transcript
         * corrected in review is what gets read. Accepting or skipping
         * returns to the review queue, and the saved script positions stay
         * where they were. */
        fun retakeIntent(context: Context, promptId: String, category: String, text: String) =
            Intent(context, RecordingActivity::class.java).apply {
                putExtra(EXTRA_RETAKE_PROMPT_ID, promptId)
                putExtra(EXTRA_RETAKE_CATEGORY, category)
                putExtra(EXTRA_RETAKE_TEXT, text)
            }
    }

    private lateinit var binding: ActivityRecordingBinding
    private lateinit var repository: ScriptRepository
    private lateinit var prompts: MutableList<Prompt>
    private var categoryFilter: String? = null
    private var index = 0
    private var isRetakeMode = false
    private var promptHasTake = false

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
            if (seconds >= MAX_TAKE_SECONDS) {
                stopRecording()
                Toast.makeText(this@RecordingActivity, "Take stopped at 5 minutes", Toast.LENGTH_LONG).show()
                return
            }
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
        val retakePromptId = intent.getStringExtra(EXTRA_RETAKE_PROMPT_ID)
        if (retakePromptId != null) {
            isRetakeMode = true
            prompts = mutableListOf(
                Prompt(
                    id = retakePromptId,
                    category = intent.getStringExtra(EXTRA_RETAKE_CATEGORY) ?: "custom",
                    text = intent.getStringExtra(EXTRA_RETAKE_TEXT) ?: "",
                )
            )
            index = 0
            binding.backButton.visibility = android.view.View.GONE
        } else {
            categoryFilter = intent.getStringExtra(EXTRA_CATEGORY)
            val allPrompts = repository.loadActive()
            val filter = categoryFilter
            prompts = (if (filter == null) allPrompts else allPrompts.filter { it.category == filter }).toMutableList()
            index = if (filter == null) SessionManager.promptIndex(this) else SessionManager.categoryPromptIndex(this, filter)
        }

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
        binding.skipButton.setOnClickListener { if (promptHasTake) advance() else promptSkipReason() }
        binding.backButton.setOnClickListener { goBack() }
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
                    val updated = p.copy(text = text)
                    prompts[index] = updated
                    // prompts may be a category-filtered subset — patch the one
                    // prompt inside the full script rather than overwriting it
                    // with just this subset.
                    val fullScript = repository.loadActive().toMutableList()
                    val fullIndex = fullScript.indexOfFirst { it.id == p.id }
                    if (fullIndex >= 0) fullScript[fullIndex] = updated
                    repository.saveActive(fullScript, scriptVersion)
                    binding.promptText.text = text
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCurrentPrompt() {
        if (index >= prompts.size) {
            showFinished()
            goToReview()
            return
        }
        val p = prompts[index]
        binding.progressText.text = if (isRetakeMode) "Retake" else "${index + 1} / ${prompts.size}"
        binding.categoryText.text = p.category.replace('_', ' ')
        binding.promptText.text = p.text
        binding.elapsedTimeText.text = "0.0s"
        binding.warningText.visibility = android.view.View.GONE
        binding.levelMeter.clear()
        resetTakeState()
        refreshExistingTake(p)
    }

    /** Past the last prompt only Back and the review queue stay usable, so
     * nothing can be recorded or skipped against a prompt that is not there. */
    private fun showFinished() {
        // The last take is already saved; forget it so Back is not blocked.
        currentWavFile = null
        currentClipId = null
        pendingQuality = null
        binding.progressText.text = "${prompts.size} / ${prompts.size}"
        binding.promptText.text = "All prompts here are done"
        binding.recordButton.isEnabled = false
        binding.replayButton.isEnabled = false
        binding.retakeButton.isEnabled = false
        binding.acceptButton.isEnabled = false
        binding.skipButton.isEnabled = false
        binding.editPromptButton.isEnabled = false
        binding.backButton.isEnabled = !isRetakeMode && prompts.isNotEmpty()
    }

    /** A prompt reached with Back, or already recorded in another pass of
     * this session, has a take: Skip then reads Next and moves on without
     * writing a skip record. */
    private fun refreshExistingTake(prompt: Prompt) {
        promptHasTake = false
        binding.skipButton.text = getString(com.quantilytix.izwi.R.string.recording_skip)
        if (isRetakeMode) return
        val app = application as IzwiApplication
        lifecycleScope.launch {
            val count = app.database.clipDao().countAcceptedForPrompt(sessionId, prompt.id)
            if (prompts.getOrNull(index)?.id != prompt.id) return@launch
            promptHasTake = count > 0
            binding.skipButton.text = getString(
                if (promptHasTake) com.quantilytix.izwi.R.string.recording_next else com.quantilytix.izwi.R.string.recording_skip
            )
        }
    }

    /** Steps back one prompt to redo it. Only offered between takes, so an
     * unsaved take is never dropped by it. */
    private fun goBack() {
        if (isRetakeMode || isRecording || currentWavFile != null || index <= 0) return
        index = minOf(index, prompts.size) - 1
        saveIndex()
        showCurrentPrompt()
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
        binding.backButton.isEnabled = !isRetakeMode && index > 0
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
        // A two-minute free-form take outlasts a typical screen timeout, and
        // once the screen sleeps Android 9+ can feed a backgrounded app
        // silence from the microphone.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.recordButton.text = getString(com.quantilytix.izwi.R.string.recording_stop)
        binding.skipButton.isEnabled = false
        binding.editPromptButton.isEnabled = false
        binding.backButton.isEnabled = false
        elapsedStartMs = System.currentTimeMillis()
        elapsedHandler.post(elapsedRunnable)
    }

    private fun stopRecording() {
        val file = recorder.stop()
        isRecording = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            val bounds = QualityAnalyzer.boundsForCategory(prompts[index].category)
            val result = withContext(Dispatchers.IO) { QualityAnalyzer.analyze(file, bounds) }
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
            // Disabled until the next prompt shows, so a double tap cannot
            // save twice and jump a prompt.
            binding.acceptButton.isEnabled = false
            binding.retakeButton.isEnabled = false
            replaceEarlierTakesThenPersist(clip) {
                binding.acceptButton.isEnabled = true
                binding.retakeButton.isEnabled = true
            }
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

    /**
     * A redo after Back leaves an earlier usable take of the same prompt in
     * this session. If no upload batch has claimed it yet, the speaker picks:
     * Replace marks the earlier take retake (its audio stays on the device
     * and the review queue can restore it), Keep both saves alongside it.
     * Dismissing the dialog saves nothing and keeps the take pending.
     */
    private fun replaceEarlierTakesThenPersist(clip: ClipEntity, onCancelled: () -> Unit) {
        val dao = (application as IzwiApplication).database.clipDao()
        lifecycleScope.launch {
            val earlier = dao.getUnbatchedTakesForPrompt(sessionId, clip.promptId)
            if (earlier.isEmpty()) {
                persistAndAdvance(clip)
                return@launch
            }
            AlertDialog.Builder(this@RecordingActivity)
                .setTitle("Replace the earlier take?")
                .setMessage("This prompt already has a take in this session. Replace marks it for retake; its audio stays on the device.")
                .setPositiveButton("Replace") { _, _ ->
                    lifecycleScope.launch {
                        for (old in earlier) dao.update(old.copy(reviewStatus = ReviewStatus.RETAKE))
                        persistAndAdvance(clip)
                    }
                }
                .setNegativeButton("Keep both") { _, _ -> persistAndAdvance(clip) }
                .setOnCancelListener { onCancelled() }
                .show()
        }
    }

    private fun persistAndAdvance(clip: ClipEntity) {
        val app = application as IzwiApplication
        lifecycleScope.launch {
            app.database.clipDao().upsert(clip)
            advance()
        }
    }

    private fun advance() {
        index += 1
        saveIndex()
        showCurrentPrompt()
    }

    /** Retake mode never moves the saved positions: it works on one prompt
     * outside the script order. */
    private fun saveIndex() {
        if (isRetakeMode) return
        val filter = categoryFilter
        if (filter == null) {
            SessionManager.setPromptIndex(this, index)
        } else {
            SessionManager.setCategoryPromptIndex(this, filter, index)
        }
    }

    private fun goToReview() {
        // A retake was opened from the review queue, so returning to it means
        // finishing here instead of stacking a second review screen on top.
        if (isRetakeMode) {
            finish()
            return
        }
        startActivity(ReviewQueueActivity.intent(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        elapsedHandler.removeCallbacks(elapsedRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mediaPlayer?.release()
        if (isRecording) recorder.cancel()
    }
}
