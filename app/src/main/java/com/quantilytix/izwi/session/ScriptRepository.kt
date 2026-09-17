package com.quantilytix.izwi.session

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Prompt(val id: String, val category: String, val text: String)

/**
 * Loads the recording script. Prefers a persisted, user-editable active
 * script over the bundled seed asset — recording sessions always read
 * through [loadActive] / [activeScriptVersion] so an in-app fix or an
 * imported script takes effect without a rebuild.
 */
class ScriptRepository(private val context: Context) {

    private val activeScriptFile: File
        get() = File(File(context.filesDir, "scripts").apply { mkdirs() }, "active_script.json")

    fun loadActive(): List<Prompt> {
        val file = activeScriptFile
        return if (file.exists()) {
            parse(file.readText())
        } else {
            load()
        }
    }

    fun activeScriptVersion(): String {
        val file = activeScriptFile
        return if (file.exists()) {
            JSONObject(file.readText()).optString("script_version", "custom")
        } else {
            scriptVersion()
        }
    }

    fun hasCustomScript(): Boolean = activeScriptFile.exists()

    fun saveActive(prompts: List<Prompt>, scriptVersion: String = "custom") {
        val root = JSONObject()
        root.put("script_version", scriptVersion)
        root.put("language", "sn")
        val array = JSONArray()
        for (p in prompts) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("category", p.category)
            obj.put("text", p.text)
            array.put(obj)
        }
        root.put("prompts", array)
        activeScriptFile.writeText(root.toString(2))
    }

    fun resetToDefault() {
        activeScriptFile.delete()
    }

    /** Accepts either the app's own JSON script schema or a plain text file
     * with one prompt per line — whichever a speaker or reviewer finds
     * easier to hand-edit outside the app. */
    fun importScript(uri: Uri, contentResolver: ContentResolver): List<Prompt> {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("could not read the selected file")
        return try {
            parse(text)
        } catch (e: Exception) {
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapIndexed { i, line -> Prompt(id = "custom_${i + 1}", category = "custom", text = line) }
        }
    }

    private fun parse(json: String): List<Prompt> {
        val root = JSONObject(json)
        val array = root.getJSONArray("prompts")
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Prompt(
                id = obj.optString("id", "custom_${UUID.randomUUID().toString().take(8)}"),
                category = obj.optString("category", "custom"),
                text = obj.getString("text"),
            )
        }
    }

    private fun load(assetName: String = "script_v1.json"): List<Prompt> {
        val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
        return parse(json)
    }

    private fun scriptVersion(assetName: String = "script_v1.json"): String {
        val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
        return JSONObject(json).optString("script_version", "v1")
    }
}
