package com.quantilytix.izwi.session

/**
 * One prompt of a script. [version] starts at 1 and goes up by one with
 * every text edit, under the same [id], so each take can record which text
 * it was read from. [editedAtUtc] stamps the latest edit; null means the
 * text is as the pack shipped it.
 */
data class Prompt(
    val id: String,
    val category: String,
    val text: String,
    val version: Int = 1,
    val editedAtUtc: String? = null,
) {
    /** The single rule for a text change. Unchanged text keeps its version,
     * so saving a dialog without edits never marks takes as outdated. The
     * dialog trims what it saves, so stored text with stray outer spaces (an
     * imported file, a v0.6.0 edit) also counts as unchanged. */
    fun withText(newText: String, nowUtc: String): Prompt {
        val trimmed = newText.trim()
        if (trimmed == text.trim()) return this
        return copy(text = trimmed, version = version + 1, editedAtUtc = nowUtc)
    }
}

/** Script languages as ISO 639-1 codes, the same codes ClipEntity.language
 * and the script JSON carry. "nd" is North Ndebele (ISO 639-3 nde), the
 * isiNdebele spoken in Zimbabwe. */
object Languages {
    const val SHONA = "sn"
    const val NDEBELE = "nd"
    val ALL = listOf(SHONA, NDEBELE)

    fun displayName(code: String): String = when (code) {
        NDEBELE -> "isiNdebele"
        else -> "Shona"
    }

    /**
     * Prompt ids are unique across languages. Every language except Shona
     * prefixes its ids with its own code; Shona keeps bare ids, so takes
     * from v0.6.0 and earlier still match their prompts, and only gets an
     * "sn_" prefix on an id that would read as another language's.
     */
    fun namespacedId(id: String, language: String): String = when {
        language == SHONA ->
            if (ALL.any { it != SHONA && id.startsWith("${it}_") }) "${SHONA}_$id" else id
        id.startsWith("${language}_") -> id
        else -> "${language}_$id"
    }
}
