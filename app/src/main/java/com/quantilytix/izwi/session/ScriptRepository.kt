package com.quantilytix.izwi.session

import android.content.Context
import org.json.JSONObject

data class Prompt(val id: String, val category: String, val text: String)

class ScriptRepository(private val context: Context) {

    fun load(assetName: String = "script_v1.json"): List<Prompt> {
        val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val array = root.getJSONArray("prompts")
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Prompt(
                id = obj.getString("id"),
                category = obj.getString("category"),
                text = obj.getString("text"),
            )
        }
    }

    fun scriptVersion(assetName: String = "script_v1.json"): String {
        val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
        return JSONObject(json).optString("script_version", "v1")
    }
}
