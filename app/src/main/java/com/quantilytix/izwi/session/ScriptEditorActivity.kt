package com.quantilytix.izwi.session

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.R
import com.quantilytix.izwi.data.PromptTakeSummary
import com.quantilytix.izwi.databinding.ActivityScriptEditorBinding
import com.quantilytix.izwi.recording.RecordingActivity
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Prompts screen: search, filter and jump to any prompt, fix its text in
 * place, and pick each language's script pack. Reachable from session
 * setup, the recording screen, and the review queue. Tapping a prompt opens
 * the recorder on it inside the list on screen, so Next and Back carry on
 * from there.
 *
 * Built for 6,106 prompts: take status for every prompt comes from one
 * grouped query, rows are rebuilt and filtered off the main thread, search
 * waits for a pause in typing, and the list diffs instead of redrawing.
 * Every edit persists immediately through ScriptRepository, the same
 * versioned save path the recorder uses.
 */
class ScriptEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SPEAKER_ID = "speaker_id"
        private const val EXTRA_LANGUAGE = "language"
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** [language] is for session setup, before a session exists; with
         * one active, the screen shows the session's language. */
        fun intent(context: Context, speakerId: String? = null, language: String? = null) =
            Intent(context, ScriptEditorActivity::class.java).apply {
                if (!speakerId.isNullOrBlank()) putExtra(EXTRA_SPEAKER_ID, speakerId)
                if (!language.isNullOrBlank()) putExtra(EXTRA_LANGUAGE, language)
            }
    }

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var repository: ScriptRepository
    private lateinit var adapter: PromptAdapter
    private var speakerId: String = ""
    private var language: String = Languages.SHONA
    private var activeScript: ActiveScript? = null
    private var prompts: List<Prompt> = emptyList()
    private var summaries: Map<String, PromptTakeSummary> = emptyMap()
    private var rows: List<PromptRow> = emptyList()
    private var query = ""
    private var statusFilter = PromptFilter.ALL
    private var categoryFilter: String? = null

    private var loadJob: Job? = null
    private var summaryJob: Job? = null
    private var rowsJob: Job? = null
    private var filterJob: Job? = null
    private var searchJob: Job? = null

    // Set by any change that can replace most rows (search, filter, pack,
    // language) and kept until a submit uses it, so a take saved meanwhile
    // cannot cancel it into a diff over thousands of rows.
    private var replaceListOnNextSubmit = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) { repository.importScript(language, uri, contentResolver) }
                if (pack == null) {
                    toast("No prompts found in that file")
                    return@launch
                }
                categoryFilter = null
                reloadScript { toast("Imported ${prompts.size} prompts as ${pack.label}") }
            } catch (e: Exception) {
                Toast.makeText(this@ScriptEditorActivity, "Could not import: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.headerContainer.applySystemBarInsetPadding(applyTop = true)
        binding.promptsRecycler.applySystemBarInsetPadding(applyBottom = true)

        speakerId = intent.getStringExtra(EXTRA_SPEAKER_ID)?.takeIf { it.isNotBlank() }
            ?: SessionManager.speakerId(this)
        language = when {
            SessionManager.hasActiveSession(this) -> SessionManager.language(this)
            else -> intent.getStringExtra(EXTRA_LANGUAGE)
                ?: SessionManager.rememberedLanguage(this, speakerId)
                ?: Languages.SHONA
        }

        repository = ScriptRepository(this)
        adapter = PromptAdapter(
            onOpen = { row -> openRecorderAt(row) },
            onEdit = { row -> editPrompt(row.prompt) },
            onDelete = { row -> deletePrompt(row.prompt) },
        )
        binding.promptsRecycler.layoutManager = LinearLayoutManager(this)
        binding.promptsRecycler.adapter = adapter
        binding.promptsRecycler.setHasFixedSize(true)

        binding.languageToggle.check(
            if (language == Languages.NDEBELE) R.id.languageNdebeleButton else R.id.languageShonaButton
        )
        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val code = if (checkedId == R.id.languageNdebeleButton) Languages.NDEBELE else Languages.SHONA
            if (code != language) switchLanguage(code)
        }

        binding.searchInput.doAfterTextChanged { text ->
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                query = text?.toString().orEmpty()
                applyFilters(scrollToTop = true)
            }
        }
        binding.searchInput.setOnEditorActionListener { view, actionId, _ ->
            // IME_NULL is Enter on a hardware keyboard.
            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_NULL) {
                return@setOnEditorActionListener false
            }
            // The search key puts the keyboard away so the results are in reach.
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
            true
        }
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            statusFilter = when (checkedIds.firstOrNull()) {
                R.id.chipOutstanding -> PromptFilter.OUTSTANDING
                R.id.chipRecorded -> PromptFilter.RECORDED
                R.id.chipNeedsRetake -> PromptFilter.NEEDS_RETAKE
                else -> PromptFilter.ALL
            }
            applyFilters(scrollToTop = true)
        }

        binding.packButton.setOnClickListener { choosePack() }
        binding.categoryButton.setOnClickListener { chooseCategory() }
        binding.recordCategoryButton.setOnClickListener { categoryFilter?.let { recordCategory(it) } }
        binding.addPromptButton.setOnClickListener { addPrompt() }
        binding.resetScriptButton.setOnClickListener { resetPack() }

        observeTakes()
        reloadScript()
    }

    private fun reloadScript(then: (() -> Unit)? = null) {
        val lang = language
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val script = withContext(Dispatchers.IO) { repository.loadActiveScript(lang) }
            if (lang != language) return@launch
            activeScript = script
            prompts = script.prompts
            if (categoryFilter != null && prompts.none { it.category == categoryFilter }) categoryFilter = null
            renderPackLabel()
            rebuildRows(scrollToTop = true)
            then?.invoke()
        }
    }

    /** One grouped query for this speaker and language, re-emitted by Room
     * whenever a take is saved or its status changes. */
    private fun observeTakes() {
        summaryJob?.cancel()
        summaries = emptyMap()
        if (speakerId.isBlank()) return
        val lang = language
        val dao = (application as IzwiApplication).database.clipDao()
        summaryJob = lifecycleScope.launch {
            dao.observePromptSummaries(speakerId, lang)
                .map { list -> list.associateBy { it.promptId } }
                .flowOn(Dispatchers.Default)
                .collect { bySummary ->
                    summaries = bySummary
                    rebuildRows(scrollToTop = false)
                }
        }
    }

    private fun rebuildRows(scrollToTop: Boolean) {
        if (scrollToTop) replaceListOnNextSubmit = true
        val promptsNow = prompts
        val summariesNow = summaries
        rowsJob?.cancel()
        rowsJob = lifecycleScope.launch {
            rows = withContext(Dispatchers.Default) { PromptSearch.buildRows(promptsNow, summariesNow) }
            renderProgress()
            renderCategoryButton()
            applyFilters(scrollToTop = false)
        }
    }

    private fun applyFilters(scrollToTop: Boolean) {
        if (scrollToTop) replaceListOnNextSubmit = true
        val rowsNow = rows
        val q = query
        val filter = statusFilter
        val category = categoryFilter
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { PromptSearch.filter(rowsNow, q, filter, category) }
            if (replaceListOnNextSubmit) {
                // A new search or filter can replace most of 6,106 rows, where
                // a diff costs more than it saves: clear, then insert. A take
                // saved or a text edit keeps the diff, which touches a row or two.
                replaceListOnNextSubmit = false
                adapter.submitList(null)
                adapter.submitList(result.rows) { binding.promptsRecycler.scrollToPosition(0) }
            } else {
                adapter.submitList(result.rows)
            }
            binding.chipAll.text = "All ${count(result.counts[PromptFilter.ALL])}"
            binding.chipOutstanding.text = "Outstanding ${count(result.counts[PromptFilter.OUTSTANDING])}"
            binding.chipRecorded.text = "Recorded ${count(result.counts[PromptFilter.RECORDED])}"
            binding.chipNeedsRetake.text = "Needs retake ${count(result.counts[PromptFilter.NEEDS_RETAKE])}"
            binding.resultCountText.text = "Showing ${count(result.rows.size)} of ${count(rowsNow.size)}"
            // Nothing to say until the script has loaded.
            binding.emptyText.visibility = if (result.rows.isEmpty() && activeScript != null) View.VISIBLE else View.GONE
            binding.emptyText.text = if (rowsNow.isEmpty()) "This pack has no prompts yet." else "No prompts match."
            binding.recordCategoryButton.visibility = if (category != null) View.VISIBLE else View.GONE
        }
    }

    private fun count(n: Int?): String = String.format("%,d", n ?: 0)

    private fun renderPackLabel() {
        val script = activeScript ?: return
        binding.packButton.text = "Pack: ${script.pack.label}" + (if (script.edited) " (edited)" else "") + " ▾"
    }

    private fun renderProgress() {
        val total = rows.size
        val recorded = rows.count { it.status.isRecorded }
        val retake = rows.count { it.status.needsRetake }
        binding.progressText.text = if (speakerId.isBlank()) {
            "${count(total)} prompts. Enter a speaker ID on the first screen to see progress."
        } else {
            "${Languages.displayName(language)}: recorded ${count(recorded)} of ${count(total)}. " +
                "Outstanding ${count(total - recorded)}. Needs retake ${count(retake)}."
        }
        binding.progressBar.max = total.coerceAtLeast(1)
        binding.progressBar.progress = recorded
    }

    /** Recorded and total prompts per category, in script order. */
    private fun categoryCounts(): LinkedHashMap<String, Pair<Int, Int>> {
        val counts = LinkedHashMap<String, Pair<Int, Int>>()
        for (row in rows) {
            val (recorded, total) = counts[row.prompt.category] ?: (0 to 0)
            counts[row.prompt.category] = (recorded + if (row.status.isRecorded) 1 else 0) to (total + 1)
        }
        return counts
    }

    private fun renderCategoryButton() {
        val category = categoryFilter
        binding.categoryButton.text = if (category == null) {
            "All categories ▾"
        } else {
            val (recorded, total) = categoryCounts()[category] ?: (0 to 0)
            "${category.replace('_', ' ')}  ${count(recorded)} / ${count(total)} ▾"
        }
    }

    /** Least-recorded category first, as the v0.6.0 progress list had it. */
    private fun chooseCategory() {
        val counts = categoryCounts()
        val categories = counts.entries
            .sortedBy { (_, c) -> c.first.toDouble() / c.second.coerceAtLeast(1) }
            .map { it.key }
        val options = listOf<String?>(null) + categories
        val labels = options.map { category ->
            if (category == null) {
                "All categories  ${count(rows.count { it.status.isRecorded })} / ${count(rows.size)}"
            } else {
                val (recorded, total) = counts.getValue(category)
                "${category.replace('_', ' ')}  ${count(recorded)} / ${count(total)}"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Category, recorded / total")
            .setItems(labels.toTypedArray()) { _, which ->
                categoryFilter = options[which]
                renderCategoryButton()
                applyFilters(scrollToTop = true)
            }
            .show()
    }

    /** Language changes apply to the session too, when one is active: new
     * takes are recorded in the language on screen. */
    private fun switchLanguage(code: String) {
        language = code
        if (SessionManager.hasActiveSession(this)) {
            SessionManager.setLanguage(this, code)
            toast("New takes are now recorded in ${Languages.displayName(code)}")
        } else {
            SessionManager.rememberLanguage(this, speakerId, code)
        }
        // Categories and search words belong to one language's script.
        categoryFilter = null
        searchJob?.cancel()
        query = ""
        if (binding.searchInput.text.isNotEmpty()) binding.searchInput.setText("")
        activeScript = null
        prompts = emptyList()
        rows = emptyList()
        adapter.submitList(null)
        observeTakes()
        reloadScript()
    }

    private fun choosePack() {
        val lang = language
        lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) { repository.packs(lang) }
            val edited = withContext(Dispatchers.IO) { packs.associate { it.id to repository.hasEdits(it) } }
            val activeId = activeScript?.pack?.id
            val labels = packs.map { p -> p.label + if (edited[p.id] == true) " (edited)" else "" } + "Import a file"
            AlertDialog.Builder(this@ScriptEditorActivity)
                .setTitle("${Languages.displayName(lang)} script pack")
                .setSingleChoiceItems(labels.toTypedArray(), packs.indexOfFirst { it.id == activeId }) { dialog, which ->
                    dialog.dismiss()
                    when {
                        which == packs.size -> importLauncher.launch(arrayOf("*/*"))
                        packs[which].id != activeId -> switchPack(packs[which])
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun switchPack(pack: ScriptPack) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.setActivePack(language, pack.id) }
            categoryFilter = null
            reloadScript { toast("Now on ${pack.label}. Every pack keeps its own edits.") }
        }
    }

    /** Opens the recorder on [row] inside the list on screen. The list is
     * saved for the recorder, which steps through it with Next and Back. */
    private fun openRecorderAt(row: PromptRow) {
        if (!SessionManager.hasActiveSession(this)) {
            Toast.makeText(this, "Start a session first, then tap a prompt to record it", Toast.LENGTH_LONG).show()
            return
        }
        if (SessionManager.language(this) != language) SessionManager.setLanguage(this, language)
        val snapshot = BrowseList.Snapshot(language, listLabel(), adapter.currentList.map { it.prompt.id })
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BrowseList.save(this@ScriptEditorActivity, snapshot) }
            startActivity(RecordingActivity.browseIntent(this@ScriptEditorActivity, row.prompt.id))
            finish()
        }
    }

    private fun listLabel(): String {
        val parts = mutableListOf<String>()
        if (statusFilter != PromptFilter.ALL) parts.add(statusFilter.label)
        categoryFilter?.let { parts.add(it.replace('_', ' ')) }
        if (query.isNotBlank()) parts.add("\"${query.trim()}\"")
        return parts.joinToString(" · ").ifEmpty { "All" }
    }

    /** Jumps straight into recording just this category's prompts, from that
     * category's own saved position. Needs an active session: this screen
     * is also reachable from session setup, before one starts. */
    private fun recordCategory(category: String) {
        if (!SessionManager.hasActiveSession(this)) {
            Toast.makeText(this, "Start a session first, then come back to record by category", Toast.LENGTH_LONG).show()
            return
        }
        if (SessionManager.language(this) != language) SessionManager.setLanguage(this, language)
        startActivity(RecordingActivity.intent(this, category))
        finish()
    }

    private fun addPrompt() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Add prompt")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.addPrompt(language, text) }
                    reloadScript { toast("Added at the end, under custom") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editPrompt(prompt: Prompt) {
        val lang = language
        showEditPromptDialog(this, prompt) { text ->
            lifecycleScope.launch {
                val updated = withContext(Dispatchers.IO) { repository.editPromptText(lang, prompt.id, text) }
                if (lang != language) return@launch
                if (updated == null) {
                    toast("Could not save: this prompt is no longer in the pack")
                    return@launch
                }
                prompts = prompts.map { if (it.id == updated.id) updated else it }
                activeScript = activeScript?.let { it.copy(prompts = prompts, edited = it.pack.isBundled) }
                renderPackLabel()
                rebuildRows(scrollToTop = false)
            }
        }
    }

    private fun deletePrompt(prompt: Prompt) {
        AlertDialog.Builder(this)
            .setTitle("Delete this prompt?")
            .setMessage("Its takes stay on the device and in the review queue.")
            .setPositiveButton("Delete") { _, _ ->
                val lang = language
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.deletePrompt(lang, prompt.id) }
                    if (lang != language) return@launch
                    prompts = prompts.filter { it.id != prompt.id }
                    activeScript = activeScript?.let { it.copy(prompts = prompts, edited = it.pack.isBundled) }
                    renderPackLabel()
                    rebuildRows(scrollToTop = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetPack() {
        val pack = activeScript?.pack ?: return
        if (!pack.isBundled) {
            toast("Imported packs have no shipped text to go back to")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Reset ${pack.label}?")
            .setMessage("This puts back the shipped text of every prompt in this pack and removes prompts you added. Takes stay as they are.")
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.resetActivePack(language) }
                    categoryFilter = null
                    reloadScript()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
