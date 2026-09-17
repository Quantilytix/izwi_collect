package com.quantilytix.izwi.session

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Persists the active recording session across process death so a session
 * resumes safely after an app restart, per PLAN_B Phase 2.
 */
object SessionManager {
    private const val PREFS = "izwi_session"
    private const val KEY_SPEAKER_ID = "speaker_id"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_SCRIPT_VERSION = "script_version"
    private const val KEY_PROMPT_INDEX = "prompt_index"

    fun startNewSession(context: Context, speakerId: String, consentVersion: String, scriptVersion: String): String {
        val sessionId = generateSessionId(speakerId)
        prefs(context).edit()
            .putString(KEY_SPEAKER_ID, speakerId)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_CONSENT_VERSION, consentVersion)
            .putString(KEY_SCRIPT_VERSION, scriptVersion)
            .putInt(KEY_PROMPT_INDEX, 0)
            .apply()
        return sessionId
    }

    fun hasActiveSession(context: Context): Boolean = prefs(context).contains(KEY_SESSION_ID)

    fun speakerId(context: Context): String = prefs(context).getString(KEY_SPEAKER_ID, "") ?: ""
    fun sessionId(context: Context): String = prefs(context).getString(KEY_SESSION_ID, "") ?: ""
    fun consentVersion(context: Context): String = prefs(context).getString(KEY_CONSENT_VERSION, "") ?: ""
    fun scriptVersion(context: Context): String = prefs(context).getString(KEY_SCRIPT_VERSION, "") ?: ""

    fun promptIndex(context: Context): Int = prefs(context).getInt(KEY_PROMPT_INDEX, 0)

    fun setPromptIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_PROMPT_INDEX, index).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun nowUtcIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun generateSessionId(speakerId: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val stamp = fmt.format(Date())
        return "session_${stamp}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
