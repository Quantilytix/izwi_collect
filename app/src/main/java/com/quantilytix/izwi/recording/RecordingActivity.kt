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
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.R
import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ReviewStatus
import com.quantilytix.izwi.databinding.ActivityRecordingBinding
import com.quantilytix.izwi.quality.QualityAnalyzer
import com.quantilytix.izwi.quality.QualityResult
import com.quantilytix.izwi.review.ReviewQueueActivity
import com.quantilytix.izwi.session.BrowseList
import com.quantilytix.izwi.session.Languages
import com.quantilytix.izwi.session.Prompt
import com.quantilytix.izwi.session.ScriptEditorActivity
import com.quantilytix.izwi.session.ScriptRepository
import com.quantilytix.izwi.session.SessionManager
import com.quantilytix.izwi.session.showEditPromptDialog
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class RecordingActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CATEGORY = "category_filter"
        private const val EXTRA_BROWSE_START = "browse_start_prompt_id"
        private const val EXTRA_RETAKE_PROMPT_ID = "retake_prompt_id"
        private const val EXTRA_RETAKE_CATEGORY = "retake_category"
        private const val EXTRA_RETAKE_TEXT = "retake_text"
        private const val EXTRA_RETAKE_LANGUAGE = "retake_language"
        private const val EXTRA_RETAKE_PROMPT_VERSION = "retake_prompt_version"
        private const val EXTRA_RETAKE_SCRIPT_VERSION = "retake_script_version"
        private const val STATE_PROMPT_ID = "current_prompt_id"

        // Free-form takes are flagged past 180s; stopping a forgotten take at
        // 300s keeps the file small enough for QualityAnalyzer to load whole.
        private const val MAX_TAKE_SECONDS = 300.0

        /** [categoryFilter] narrows the session to just that category's
         * prompts, e.g. "go record all the stock_inventory clips", with
         * its own resume position, independent of the whole-script order. */
        fun intent(context: Context, categoryFilter: String? = null) =
            Intent(context, RecordingActivity::class.java).apply {
                if (!categoryFilter.isNullOrBlank()) {
                    putExtra(EXTRA_CATEGORY, categoryFilter)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }

        /** Opens the recorder on [promptId] inside the list the Prompts
         * screen saved in BrowseList, so Next and Back walk that list. Reuses
         * the recorder already in the back stack, so a jump never stacks a
         * second recorder on the first. */
        fun browseIntent(context: Context, promptId: String) =
            Intent(context, RecordingActivity::class.java).apply {
                putExtra(EXTRA_BROWSE_START, promptId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        /** Opens the recorder on one prompt only, for a clip marked retake in
         * the review queue. She reads the clip's transcript, so a transcript
         * corrected in review is what gets read, and the new take keeps the
         * clip's language and prompt version. Accepting or skipping returns
         * to the review queue, and the saved script positions stay where
         * they were. */
        fun retakeIntent(context: Context, clip: ClipEntity) =
            Intent(context, RecordingActivity::class.java).apply {
                putExtra(EXTRA_RETAKE_PROMPT_ID, clip.promptId)
                putExtra(EXTRA_RETAKE_CATEGORY, clip.category)
                putExtra(EXTRA_RETAKE_TEXT, clip.transcript)
                putExtra(EXTRA_RETAKE_LANGUAGE, clip.language)
                putExtra(EXTRA_RETAKE_PROMPT_VERSION, clip.promptVersion)
                putExtra(EXTRA_RETAKE_SCRIPT_VERSION, clip.scriptVersion)
            }
    }

    /** Which prompts Next and Back walk: the whole script, one category, a
     * list from the Prompts screen, or a single retake from review. */
    private enum class Mode { SCRIPT, CATEGORY, BROWSE, RETAKE }

    private lateinit var binding: ActivityRecordingBinding
    private lateinit var repository: ScriptRepository
    private var mode = Mode.SCRIPT
    private var prompts: MutableList<Prompt> = mutableListOf()
    private var allPrompts: List<Prompt> = emptyList()
    private var browseIds: List<String> = emptyList()
    private var browseLabel = ""
    private var categoryFilter: String? = null
    private var language = Languages.SHONA
    private var packId = ""
    private var scriptVersion = ""
    private var index = 0
    private var promptHasTake = false
    private val isRetakeMode get() = mode == Mode.RETAKE

    // The Prompts screen can change the language, the pack or a prompt's
    // text while this screen waits underneath; it re-reads them on return.
    private var wasStopped = false
    private var navigatedByIntent = false
    private var reloadAfterTake = false

    // False until the first script read finishes off the main thread.
    private var loaded = false

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
    private val deviceModel get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val hasUnsavedTake get() = isRecording || currentWavFile != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetPadding(applyTop = true, applyBottom = true)

        repository = ScriptRepository(this)
        wireButtons()
        showLoading()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        // The first read of a script in this process parses all of it, 6,106
        // prompts for the read script, which stalls the main thread for a
        // second or more. It runs on IO and fills the store's cache; every
        // later read, including applyNavigation's, comes from that cache.
        val restorePromptId = savedInstanceState?.getString(STATE_PROMPT_ID)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.loadActiveScript(SessionManager.language(this@RecordingActivity)) }
            applyNavigation(intent, restorePromptId)
            loaded = true
            showCurrentPrompt()
            if (index >= prompts.size) goToReview()
        }
    }

    private fun showLoading() {
        binding.progressText.text = ""
        binding.categoryText.text = ""
        binding.promptText.text = "Loading prompts"
        binding.recordButton.isEnabled = false
        binding.replayButton.isEnabled = false
        binding.retakeButton.isEnabled = false
        binding.acceptButton.isEnabled = false
        binding.skipButton.isEnabled = false
        binding.backButton.isEnabled = false
        binding.editPromptButton.isEnabled = false
        binding.promptsButton.isEnabled = false
    }

    /** A jump from the Prompts screen, or Record this category, while this
     * recorder waits in the back stack. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!loaded) {
            // The first load is still running; it reads this intent when done.
            setIntent(intent)
            return
        }
        if (hasUnsavedTake) {
            Toast.makeText(this, "Accept or retake this take first, then jump", Toast.LENGTH_LONG).show()
            return
        }
        setIntent(intent)
        applyNavigation(intent, null)
        navigatedByIntent = true
        saveIndex()
        showCurrentPrompt()
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onResume() {
        super.onResume()
        if (wasStopped && !navigatedByIntent) reloadScriptIfIdle()
        wasStopped = false
        navigatedByIntent = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PROMPT_ID, prompts.getOrNull(index)?.id)
    }

    private fun applyNavigation(intent: Intent, restorePromptId: String?) {
        val retakePromptId = intent.getStringExtra(EXTRA_RETAKE_PROMPT_ID)
        if (retakePromptId != null) {
            mode = Mode.RETAKE
            language = intent.getStringExtra(EXTRA_RETAKE_LANGUAGE) ?: SessionManager.language(this)
            scriptVersion = intent.getStringExtra(EXTRA_RETAKE_SCRIPT_VERSION) ?: repository.activeScriptVersion(language)
            prompts = mutableListOf(
                Prompt(
                    id = retakePromptId,
                    category = intent.getStringExtra(EXTRA_RETAKE_CATEGORY) ?: "custom",
                    text = intent.getStringExtra(EXTRA_RETAKE_TEXT) ?: "",
                    version = intent.getIntExtra(EXTRA_RETAKE_PROMPT_VERSION, 1),
                )
            )
            index = 0
        } else {
            language = SessionManager.language(this)
            categoryFilter = intent.getStringExtra(EXTRA_CATEGORY)
            val browseStart = intent.getStringExtra(EXTRA_BROWSE_START)
            mode = when {
                browseStart != null -> Mode.BROWSE
                categoryFilter != null -> Mode.CATEGORY
                else -> Mode.SCRIPT
            }
            if (mode == Mode.BROWSE) {
                val snapshot = BrowseList.load(this)?.takeIf { it.language == language }
                browseIds = snapshot?.ids ?: listOf(browseStart!!)
                browseLabel = snapshot?.label ?: ""
            }
            loadScript()
            index = when {
                restorePromptId != null -> indexOfPrompt(restorePromptId) ?: savedPosition()
                mode == Mode.BROWSE -> indexOfPrompt(browseStart!!) ?: 0
                else -> savedPosition()
            }
        }
        binding.backButton.visibility = if (isRetakeMode) View.GONE else View.VISIBLE
        binding.promptsButton.visibility = if (isRetakeMode) View.GONE else View.VISIBLE
    }

    /** Reads the session language's active pack and rebuilds the list for
     * the current mode. Prompts deleted since a browse list was saved drop
     * out of it. */
    private fun loadScript() {
        val script = repository.loadActiveScript(language)
        packId = script.pack.id
        scriptVersion = script.scriptVersion
        allPrompts = script.prompts
        prompts = when (mode) {
            Mode.SCRIPT -> allPrompts
            Mode.CATEGORY -> allPrompts.filter { it.category == categoryFilter }
            Mode.BROWSE -> {
                val byId = allPrompts.associateBy { it.id }
                browseIds.mapNotNull { byId[it] }
            }
            Mode.RETAKE -> prompts
        }.toMutableList()
    }

    private fun indexOfPrompt(promptId: String): Int? = prompts.indexOfFirst { it.id == promptId }.takeIf { it >= 0 }

    private fun savedPosition(): Int {
        val category = if (mode == Mode.CATEGORY) categoryFilter else null
        return SessionManager.position(this, language, packId, category).resolveIn(prompts)
    }

    /** Back from the Prompts screen: pick up a new language or pack, or new
     * text and deletions in the same pack, staying on the same prompt. A
     * take in progress is never disturbed; the reload waits for it. */
    private fun reloadScriptIfIdle() {
        if (!loaded || isRetakeMode) return
        if (hasUnsavedTake) {
            reloadAfterTake = true
            return
        }
        reloadScript()
    }

    private fun reloadScript() {
        reloadAfterTake = false
        val currentId = prompts.getOrNull(index)?.id
        val newLanguage = SessionManager.language(this)
        val newPackId = repository.activePack(newLanguage).id
        if (newLanguage != language || newPackId != packId) {
            // Another language or pack: start over in script order, at that
            // script's own saved position.
            language = newLanguage
            mode = Mode.SCRIPT
            categoryFilter = null
            loadScript()
            index = savedPosition()
        } else {
            loadScript()
            index = currentId?.let { indexOfPrompt(it) } ?: index.coerceAtMost(prompts.size)
        }
        showCurrentPrompt()
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
        binding.promptText.setOnLongClickListener {
            if (binding.editPromptButton.isEnabled) editCurrentPrompt()
            true
        }
        binding.promptsButton.setOnClickListener {
            startActivity(ScriptEditorActivity.intent(this, speakerId))
        }
    }

    /**
     * Fixes wording (grammar, formality) on the upcoming prompt before it's
     * read aloud, through the same versioned save path as the Prompts
     * screen: same id, version up by one, earlier takes untouched. Only
     * available while no take is in progress; editing a transcript after
     * recording it belongs in the review queue, where changing the
     * transcript to no longer match the spoken audio forces a retake.
     */
    private fun editCurrentPrompt() {
        val p = prompts.getOrNull(index) ?: return
        val lang = language
        showEditPromptDialog(this, p) { text ->
            lifecycleScope.launch {
                val updated = withContext(Dispatchers.IO) { repository.editPromptText(lang, p.id, text) }
                if (updated == null) {
                    // A retake of a prompt the active pack no longer holds:
                    // the new text applies to this take only.
                    if (isRetakeMode && prompts.getOrNull(index)?.id == p.id) {
                        prompts[index] = p.copy(text = text)
                        binding.promptText.text = text
                        Toast.makeText(this@RecordingActivity, "This prompt is not in the active pack. The edit applies to this take only.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val i = prompts.indexOfFirst { it.id == p.id }
                if (i >= 0) prompts[i] = updated
                allPrompts = allPrompts.map { if (it.id == p.id) updated else it }
                if (prompts.getOrNull(index)?.id == p.id) binding.promptText.text = updated.text
            }
        }
    }

    /** Shows the prompt at [index], or the finished state past the end. */
    private fun showCurrentPrompt() {
        if (index >= prompts.size) {
            showFinished()
            return
        }
        val p = prompts[index]
        binding.progressText.text = progressLabel("${index + 1} / ${prompts.size}")
        binding.categoryText.text = p.category.replace('_', ' ')
        binding.promptText.text = p.text
        binding.elapsedTimeText.text = "0.0s"
        binding.warningText.visibility = View.GONE
        binding.levelMeter.clear()
        resetTakeState()
        refreshExistingTake(p)
    }

    private fun progressLabel(position: String): String {
        val lang = Languages.displayName(language)
        return when (mode) {
            Mode.RETAKE -> "$lang · Retake"
            Mode.BROWSE -> "$lang · ${browseLabel.ifBlank { "List" }} · $position"
            Mode.CATEGORY -> "$lang · ${categoryFilter?.replace('_', ' ')} · $position"
            Mode.SCRIPT -> "$lang · $position"
        }
    }

    /** Past the last prompt only Back, Prompts and the review queue stay
     * usable, so nothing can be recorded or skipped against a prompt that is
     * not there. */
    private fun showFinished() {
        // The last take is already saved; forget it so Back is not blocked.
        currentWavFile = null
        currentClipId = null
        pendingQuality = null
        binding.progressText.text = progressLabel("${prompts.size} / ${prompts.size}")
        binding.categoryText.text = ""
        binding.promptText.text = "All prompts here are done"
        binding.recordButton.isEnabled = false
        binding.replayButton.isEnabled = false
        binding.retakeButton.isEnabled = false
        binding.acceptButton.isEnabled = false
        binding.skipButton.isEnabled = false
        binding.editPromptButton.isEnabled = false
        binding.backButton.isEnabled = !isRetakeMode && prompts.isNotEmpty()
        binding.promptsButton.isEnabled = !isRetakeMode
    }

    /** A prompt reached with Back, or already recorded in another pass of
     * this session, has a take: Skip then reads Next and moves on without
     * writing a skip record. */
    private fun refreshExistingTake(prompt: Prompt) {
        promptHasTake = false
        binding.skipButton.text = getString(R.string.recording_skip)
        if (isRetakeMode) return
        val app = application as IzwiApplication
        lifecycleScope.launch {
            val count = app.database.clipDao().countAcceptedForPrompt(sessionId, prompt.id)
            if (prompts.getOrNull(index)?.id != prompt.id) return@launch
            promptHasTake = count > 0
            binding.skipButton.text = getString(
                if (promptHasTake) R.string.recording_next else R.string.recording_skip
            )
        }
    }

    /** Steps back one prompt to redo it. Only offered between takes, so an
     * unsaved take is never dropped by it. */
    private fun goBack() {
        if (isRetakeMode || hasUnsavedTake || index <= 0) return
        moveTo(minOf(index, prompts.size) - 1)
    }

    private fun resetTakeState() {
        currentWavFile = null
        currentClipId = null
        pendingQuality = null
        binding.recordButton.isEnabled = true
        binding.recordButton.text = getString(R.string.recording_record)
        binding.replayButton.isEnabled = false
        binding.retakeButton.isEnabled = false
        binding.acceptButton.isEnabled = false
        binding.skipButton.isEnabled = true
        binding.editPromptButton.isEnabled = true
        binding.backButton.isEnabled = !isRetakeMode && index > 0
        binding.promptsButton.isEnabled = !isRetakeMode
        binding.warningText.visibility = View.GONE
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
        binding.recordButton.text = getString(R.string.recording_stop)
        binding.skipButton.isEnabled = false
        binding.editPromptButton.isEnabled = false
        binding.backButton.isEnabled = false
        binding.promptsButton.isEnabled = false
        elapsedStartMs = System.currentTimeMillis()
        elapsedHandler.post(elapsedRunnable)
    }

    private fun stopRecording() {
        val file = recorder.stop()
        isRecording = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        elapsedHandler.removeCallbacks(elapsedRunnable)
        binding.recordButton.text = getString(R.string.recording_record)
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
                binding.warningText.visibility = View.VISIBLE
                binding.warningText.text = result.warnings.joinToString(" · ")
            } else {
                binding.warningText.visibility = View.GONE
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
        if (reloadAfterTake) reloadScript()
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
            language = language,
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
            promptVersion = p.version,
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
                language = language,
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
                promptVersion = p.version,
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
        moveTo(index + 1)
        if (index >= prompts.size) goToReview()
    }

    private fun moveTo(newIndex: Int) {
        index = newIndex
        saveIndex()
        if (reloadAfterTake) reloadScript() else showCurrentPrompt()
    }

    /**
     * Retake mode never moves the saved positions: it works on one prompt
     * outside the script order. In a browse list the whole-script position
     * follows the prompt she is on, so the next plain resume starts there.
     */
    private fun saveIndex() {
        val id = prompts.getOrNull(index)?.id
        when (mode) {
            Mode.RETAKE -> return
            Mode.SCRIPT -> SessionManager.setPosition(this, language, packId, null, index, id)
            Mode.CATEGORY -> SessionManager.setPosition(this, language, packId, categoryFilter, index, id)
            Mode.BROWSE -> {
                if (id == null) return
                val scriptIndex = allPrompts.indexOfFirst { it.id == id }
                if (scriptIndex >= 0) SessionManager.setPosition(this, language, packId, null, scriptIndex, id)
            }
        }
    }

    private fun goToReview() {
        // A retake was opened from the review queue, so returning to it means
        // finishing here instead of stacking a second review screen on top.
        if (isRetakeMode || (!loaded && intent.hasExtra(EXTRA_RETAKE_PROMPT_ID))) {
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
