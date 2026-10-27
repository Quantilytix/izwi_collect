package com.quantilytix.izwi.session

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** A saved place in one prompt list: the prompt's id, with its index as the
 * fallback for a prompt deleted since. A null id past the end of the list. */
data class Position(val index: Int, val promptId: String?) {
    fun resolveIn(prompts: List<Prompt>): Int {
        if (promptId != null) {
            val i = prompts.indexOfFirst { it.id == promptId }
            if (i >= 0) return i
        }
        return index.coerceIn(0, prompts.size)
    }
}

/**
 * Persists the active recording session across process death so a session
 * resumes safely after an app restart, per PLAN_B Phase 2.
 */
object SessionManager {
    private const val PREFS = "izwi_session"
    private const val SPEAKER_PREFS = "izwi_speakers"
    private const val KEY_SPEAKER_ID = "speaker_id"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_SCRIPT_VERSION = "script_version"
    private const val KEY_LANGUAGE = "language"

    // v0.6.0 kept one whole-script index and one index per category. Both
    // now belong to the Shona pack the v0.6.0 script migrated into.
    private const val LEGACY_KEY_PROMPT_INDEX = "prompt_index"
    private const val LEGACY_CATEGORY_PREFIX = "prompt_index_cat_"

    fun startNewSession(
        context: Context,
        speakerId: String,
        consentVersion: String,
        scriptVersion: String,
        language: String = Languages.SHONA,
    ): String {
        val sessionId = generateSessionId(speakerId)
        prefs(context).edit()
            .putString(KEY_SPEAKER_ID, speakerId)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_CONSENT_VERSION, consentVersion)
            .putString(KEY_SCRIPT_VERSION, scriptVersion)
            .putString(KEY_LANGUAGE, language)
            .apply()
        rememberLanguage(context, speakerId, language)
        return sessionId
    }

    fun hasActiveSession(context: Context): Boolean = prefs(context).contains(KEY_SESSION_ID)

    fun speakerId(context: Context): String = prefs(context).getString(KEY_SPEAKER_ID, "") ?: ""
    fun sessionId(context: Context): String = prefs(context).getString(KEY_SESSION_ID, "") ?: ""
    fun consentVersion(context: Context): String = prefs(context).getString(KEY_CONSENT_VERSION, "") ?: ""
    fun scriptVersion(context: Context): String = prefs(context).getString(KEY_SCRIPT_VERSION, "") ?: ""

    /** The language new takes are recorded in. A session started before
     * v0.7.0 has none stored and was Shona. */
    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, null) ?: Languages.SHONA

    /** Switches the session's language from the Prompts screen, without a
     * new session. Clips carry their own language, and an upload batch
     * holds one language only, so a session can safely hold both. */
    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
        rememberLanguage(context, speakerId(context), language)
    }

    /** Each speaker's last language, kept apart from the session prefs so
     * session setup can preselect it. */
    fun rememberedLanguage(context: Context, speakerId: String): String? =
        if (speakerId.isBlank()) null else speakerPrefs(context).getString("language_$speakerId", null)

    fun rememberLanguage(context: Context, speakerId: String, language: String) {
        if (speakerId.isBlank()) return
        speakerPrefs(context).edit().putString("language_$speakerId", language).apply()
    }

    /** Resume position per language and pack, so each language keeps its
     * own progress and switching packs never moves another pack's place.
     * [category] set means the separate position of that category's own
     * subset, so recording "just the stock category" for a while doesn't
     * disturb the whole-script position, or vice versa. */
    fun position(context: Context, language: String, packId: String, category: String? = null): Position {
        val key = positionKey(language, packId, category)
        val p = prefs(context)
        return Position(p.getInt("${key}_index", 0), p.getString("${key}_id", null))
    }

    fun setPosition(context: Context, language: String, packId: String, category: String?, index: Int, promptId: String?) {
        val key = positionKey(language, packId, category)
        val edit = prefs(context).edit().putInt("${key}_index", index)
        if (promptId == null) edit.remove("${key}_id") else edit.putString("${key}_id", promptId)
        edit.apply()
    }

    /** Called once, when the v0.6.0 script moves into [shonaPackId]: copies
     * the old indexes so an in-progress session resumes where it was. */
    fun adoptLegacyPositions(context: Context, shonaPackId: String) {
        val p = prefs(context)
        val edit = p.edit()
        if (p.contains(LEGACY_KEY_PROMPT_INDEX)) {
            edit.putInt("${positionKey(Languages.SHONA, shonaPackId, null)}_index", p.getInt(LEGACY_KEY_PROMPT_INDEX, 0))
        }
        for ((key, value) in p.all) {
            if (key.startsWith(LEGACY_CATEGORY_PREFIX) && value is Int) {
                val category = key.removePrefix(LEGACY_CATEGORY_PREFIX)
                edit.putInt("${positionKey(Languages.SHONA, shonaPackId, category)}_index", value)
            }
        }
        edit.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun nowUtcIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun positionKey(language: String, packId: String, category: String?) =
        "pos_${language}_$packId" + (category?.let { "_cat_$it" } ?: "")

    private fun generateSessionId(speakerId: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val stamp = fmt.format(Date())
        return "session_${stamp}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun speakerPrefs(context: Context) =
        context.getSharedPreferences(SPEAKER_PREFS, Context.MODE_PRIVATE)
}
