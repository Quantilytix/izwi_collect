package com.quantilytix.izwi.session

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText

/**
 * The text-edit dialog the Prompts screen and the recorder share. [onSave]
 * gets the new text; callers save it through
 * ScriptRepository.editPromptText, the one versioned save path.
 */
fun showEditPromptDialog(context: Context, prompt: Prompt, onSave: (String) -> Unit) {
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setText(prompt.text)
        setSelection(text.length)
    }
    AlertDialog.Builder(context)
        .setTitle("Edit prompt")
        .setMessage(
            "This is version ${prompt.version}. Saving makes it version ${prompt.version + 1} " +
                "under the same id. Earlier takes keep the text they were read from."
        )
        .setView(input)
        .setPositiveButton("Save") { _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onSave(text)
        }
        .setNegativeButton("Cancel", null)
        .show()
}
