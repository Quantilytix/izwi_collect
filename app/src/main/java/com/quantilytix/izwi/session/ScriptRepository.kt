package com.quantilytix.izwi.session

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The recording scripts, one active pack per language. Each language picks
 * from its bundled packs or an imported file, and every edit lands in that
 * pack's own edited copy, so an in-app fix takes effect without a rebuild
 * and switching packs never loses it. Recording sessions always read
 * through [loadActiveScript] / [activeScriptVersion].
 *
 * Reads and writes parse or rewrite a whole script (6,106 prompts for the
 * read script), so screens call these off the main thread where they can.
 */
class ScriptRepository(private val context: Context) {

    private val store = ScriptStore(
        root = File(context.filesDir, "scripts"),
        readAsset = { name -> context.assets.open(name).bufferedReader().use { it.readText() } },
        // First run after v0.6.0: its single script is now the Shona slot,
        // and the saved recording positions move with it.
        onLegacyAdopted = { shonaPackId -> SessionManager.adoptLegacyPositions(context, shonaPackId) },
    )

    fun packs(language: String): List<ScriptPack> = store.packs(language)

    fun activePack(language: String): ScriptPack = store.activePack(language)

    fun setActivePack(language: String, packId: String): Boolean = store.setActivePack(language, packId)

    fun hasEdits(pack: ScriptPack): Boolean = store.hasEdits(pack)

    fun loadActiveScript(language: String): ActiveScript = store.load(language)

    fun loadActive(language: String): List<Prompt> = store.load(language).prompts

    fun activeScriptVersion(language: String): String = store.load(language).scriptVersion

    /** The one save path for prompt text: the Prompts screen and the
     * recorder's Edit prompt both call this. See [ScriptStore.editText]. */
    fun editPromptText(language: String, promptId: String, text: String): Prompt? =
        store.editText(language, promptId, text, SessionManager.nowUtcIso())

    fun addPrompt(language: String, text: String): Prompt? = store.addPrompt(language, text)

    fun deletePrompt(language: String, promptId: String) = store.deletePrompt(language, promptId)

    fun resetActivePack(language: String): Boolean = store.resetActivePack(language, SessionManager.nowUtcIso())

    /** Accepts either the app's own JSON script schema or a plain text file
     * with one prompt per line, whichever a speaker or reviewer finds
     * easier to hand-edit outside the app. The file becomes a new pack for
     * [language] and its active one. */
    fun importScript(language: String, uri: Uri, contentResolver: ContentResolver): ScriptPack? {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("could not read the selected file")
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return store.importScript(language, text, name, System.currentTimeMillis())
    }
}
