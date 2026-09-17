package com.quantilytix.izwi.session

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.quantilytix.izwi.databinding.ActivityScriptEditorBinding
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import java.util.UUID

/**
 * Lets a speaker or reviewer fix wording (grammar, formality) or bring
 * their own script before a session starts. Every change is persisted
 * immediately via ScriptRepository.saveActive — there is no separate save
 * step to forget.
 */
class ScriptEditorActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context) = Intent(context, ScriptEditorActivity::class.java)
    }

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var repository: ScriptRepository
    private lateinit var adapter: PromptAdapter
    private var prompts: MutableList<Prompt> = mutableListOf()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val imported = repository.importScript(uri, contentResolver)
            if (imported.isEmpty()) {
                Toast.makeText(this, "No prompts found in that file", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            prompts = imported.toMutableList()
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

        repository = ScriptRepository(this)
        prompts = repository.loadActive().toMutableList()

        adapter = PromptAdapter(
            onEdit = { position, prompt -> editPrompt(position, prompt) },
            onDelete = { position, _ -> deletePrompt(position) },
        )
        binding.promptsRecycler.layoutManager = LinearLayoutManager(this)
        binding.promptsRecycler.adapter = adapter
        refreshList()

        binding.addPromptButton.setOnClickListener { addPrompt() }
        binding.importScriptButton.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        binding.resetScriptButton.setOnClickListener { resetToDefault() }
    }

    private fun refreshList() {
        adapter.submit(prompts)
        binding.promptCountText.text = "${prompts.size} prompts" + if (repository.hasCustomScript()) " (custom)" else ""
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

    private fun editPrompt(position: Int, prompt: Prompt) {
        val input = EditText(this).apply { setText(prompt.text) }
        AlertDialog.Builder(this)
            .setTitle("Edit prompt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    prompts[position] = prompt.copy(text = text)
                    persistAndRefresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePrompt(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete this prompt?")
            .setPositiveButton("Delete") { _, _ ->
                prompts.removeAt(position)
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
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
