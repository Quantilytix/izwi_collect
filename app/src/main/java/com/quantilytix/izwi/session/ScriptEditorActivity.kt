package com.quantilytix.izwi.session

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.databinding.ActivityScriptEditorBinding
import com.quantilytix.izwi.recording.RecordingActivity
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Lets a speaker or reviewer fix wording (grammar, formality), bring their
 * own script, or check recording progress per category — reachable from
 * session setup, the recording screen, and the review queue, not just
 * documented in onboarding. Every script edit persists immediately via
 * ScriptRepository.saveActive — there is no separate save step to forget.
 */
class ScriptEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SPEAKER_ID = "speaker_id"
        fun intent(context: Context, speakerId: String? = null) =
            Intent(context, ScriptEditorActivity::class.java).apply {
                if (!speakerId.isNullOrBlank()) putExtra(EXTRA_SPEAKER_ID, speakerId)
            }
    }

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var repository: ScriptRepository
    private lateinit var adapter: PromptAdapter
    private var prompts: MutableList<Prompt> = mutableListOf()
    private var speakerId: String = ""
    private var categoryFilter: String? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val imported = repository.importScript(uri, contentResolver)
            if (imported.isEmpty()) {
                Toast.makeText(this, "No prompts found in that file", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            prompts = imported.toMutableList()
            categoryFilter = null
            persistAndRefresh()
            Toast.makeText(this, "Imported ${imported.size} prompts", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not import: ${e.message}", Toast.LENGTH_LONG).show()
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

        repository = ScriptRepository(this)
        prompts = repository.loadActive().toMutableList()

        adapter = PromptAdapter(
            onEdit = { _, prompt -> editPrompt(prompt) },
            onDelete = { _, prompt -> deletePrompt(prompt) },
        )
        binding.promptsRecycler.layoutManager = LinearLayoutManager(this)
        binding.promptsRecycler.adapter = adapter
        refreshList()

        binding.addPromptButton.setOnClickListener { addPrompt() }
        binding.importScriptButton.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        binding.resetScriptButton.setOnClickListener { resetToDefault() }
        binding.clearFilterButton.setOnClickListener {
            categoryFilter = null
            refreshList()
        }

        if (speakerId.isNotBlank()) {
            val app = application as IzwiApplication
            lifecycleScope.launch {
                app.database.clipDao().observeCategoryCountsForSpeaker(speakerId).collect { counts ->
                    renderCategoryProgress(counts.associate { it.category to it.count })
                }
            }
        } else {
            renderCategoryProgress(emptyMap())
        }
    }

    private fun refreshList() {
        val filter = categoryFilter
        val visible = if (filter == null) prompts else prompts.filter { it.category == filter }
        adapter.submit(visible)

        binding.promptCountText.text = if (filter == null) {
            "${prompts.size} prompts" + if (repository.hasCustomScript()) " (custom)" else ""
        } else {
            "${visible.size} / ${prompts.size} prompts — ${filter.replace('_', ' ').uppercase()} only"
        }
        binding.clearFilterButton.visibility = if (filter == null) android.view.View.GONE else android.view.View.VISIBLE

        renderCategoryProgress(lastCounts)
    }

    private var lastCounts: Map<String, Int> = emptyMap()

    private fun renderCategoryProgress(recordedByCategory: Map<String, Int>) {
        lastCounts = recordedByCategory
        val container = binding.categoryProgressContainer
        container.removeAllViews()

        val totalByCategory = prompts.groupingBy { it.category }.eachCount()
        if (totalByCategory.isEmpty()) return

        val rows = totalByCategory.map { (category, total) ->
            val recorded = recordedByCategory[category] ?: 0
            Triple(category, recorded, total)
        }.sortedBy { it.second }

        val title = TextView(this).apply {
            text = if (speakerId.isBlank()) {
                "Progress by category (enter a speaker ID to see this)"
            } else {
                "Progress by category for $speakerId — least-recorded first"
            }
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }
        container.addView(title)

        rows.forEachIndexed { i, (category, recorded, total) ->
            val isLeast = i == 0 && recorded < total
            container.addView(buildCategoryRow(category, recorded, total, isLeast))
        }
    }

    private fun buildCategoryRow(category: String, recorded: Int, total: Int, isLeast: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener {
                categoryFilter = category
                refreshList()
                binding.promptsRecycler.scrollToPosition(0)
            }
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = (if (isLeast) "↓ " else "") + category.replace('_', ' ').uppercase()
            setTextColor(if (isLeast) Color.parseColor("#C0392B") else Color.parseColor("#14231F"))
            textSize = 12.5f
            setTypeface(typeface, if (isLeast) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val count = TextView(this).apply {
            text = "$recorded / $total"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12.5f
        }
        val recordAction = TextView(this).apply {
            text = "  RECORD"
            setTextColor(Color.parseColor("#1F6F5C"))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 0, 0, 0)
            setOnClickListener { recordCategory(category) }
        }
        labelRow.addView(label)
        labelRow.addView(count)
        labelRow.addView(recordAction)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total.coerceAtLeast(1)
            progress = recorded.coerceAtMost(total)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12).apply {
                topMargin = 4
            }
        }

        row.addView(labelRow)
        row.addView(bar)
        return row
    }

    /** Jumps straight into recording just this category's prompts. Needs an
     * active session — this screen is also reachable before one starts, from
     * session setup, where there is nothing yet to attach a recording to. */
    private fun recordCategory(category: String) {
        if (!SessionManager.hasActiveSession(this)) {
            Toast.makeText(this, "Start a session first, then come back to record by category", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(RecordingActivity.intent(this, category))
    }

    private fun persistAndRefresh() {
        repository.saveActive(prompts)
        refreshList()
    }

    private fun addPrompt() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Add prompt")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    prompts.add(Prompt(id = "custom_${UUID.randomUUID().toString().take(8)}", category = "custom", text = text))
                    persistAndRefresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editPrompt(prompt: Prompt) {
        val input = EditText(this).apply { setText(prompt.text) }
        AlertDialog.Builder(this)
            .setTitle("Edit prompt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val position = prompts.indexOfFirst { it.id == prompt.id }
                if (text.isNotEmpty() && position >= 0) {
                    prompts[position] = prompt.copy(text = text)
                    persistAndRefresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePrompt(prompt: Prompt) {
        AlertDialog.Builder(this)
            .setTitle("Delete this prompt?")
            .setPositiveButton("Delete") { _, _ ->
                prompts.removeAll { it.id == prompt.id }
                persistAndRefresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetToDefault() {
        AlertDialog.Builder(this)
            .setTitle("Reset to the default script?")
            .setMessage("This discards any custom prompts and reloads the bundled seed script.")
            .setPositiveButton("Reset") { _, _ ->
                repository.resetToDefault()
                prompts = repository.loadActive().toMutableList()
                categoryFilter = null
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
