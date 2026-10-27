package com.quantilytix.izwi.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The list the speaker was browsing on the Prompts screen when she tapped a
 * prompt, so the recorder's Next and Back walk that list. Kept in a file:
 * 6,106 ids are too many to pass safely in an Intent, and the file outlives
 * process death along with the recorder it belongs to.
 */
object BrowseList {

    /** [label] names the list on the recorder, e.g. "Outstanding · stock inventory". */
    data class Snapshot(val language: String, val label: String, val ids: List<String>)

    private fun file(context: Context) = File(File(context.filesDir, "session").apply { mkdirs() }, "browse_list.json")

    fun save(context: Context, snapshot: Snapshot) {
        val obj = JSONObject()
        obj.put("language", snapshot.language)
        obj.put("label", snapshot.label)
        obj.put("ids", JSONArray(snapshot.ids))
        file(context).writeText(obj.toString())
    }

    fun load(context: Context): Snapshot? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val obj = JSONObject(f.readText())
            val ids = obj.getJSONArray("ids")
            Snapshot(
                language = obj.getString("language"),
                label = obj.optString("label", ""),
                ids = (0 until ids.length()).map { ids.getString(it) },
            )
        } catch (e: Exception) {
            null
        }
    }
}
