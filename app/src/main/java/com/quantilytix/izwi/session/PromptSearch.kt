package com.quantilytix.izwi.session

import com.quantilytix.izwi.data.PromptTakeSummary
import com.quantilytix.izwi.data.ReviewStatus
import java.text.Normalizer
import java.util.Locale

/** The Prompts screen's status chips. A prompt can sit under more than one:
 * a warning take is both Recorded and Needs retake. */
enum class PromptFilter(val label: String) {
    ALL("All"),
    OUTSTANDING("Outstanding"),
    RECORDED("Recorded"),
    NEEDS_RETAKE("Needs retake"),
}

/** Where one prompt stands for this speaker, across every session. */
data class PromptStatus(
    val takeCount: Int,
    val keptCount: Int,
    val latestStatus: String?,
    /** Every kept take was read from text that has since been edited. It
     * still counts as recorded. */
    val textChanged: Boolean,
) {
    /** An accepted or warning take exists. */
    val isRecorded get() = keptCount > 0

    /** No kept take: never recorded, or only retake or skipped takes. */
    val isOutstanding get() = keptCount == 0

    /** The latest take is a retake, a warning, or a skip. */
    val needsRetake get() = latestStatus == ReviewStatus.RETAKE ||
        latestStatus == ReviewStatus.WARNING || latestStatus == ReviewStatus.SKIPPED

    fun matches(filter: PromptFilter): Boolean = when (filter) {
        PromptFilter.ALL -> true
        PromptFilter.OUTSTANDING -> isOutstanding
        PromptFilter.RECORDED -> isRecorded
        PromptFilter.NEEDS_RETAKE -> needsRetake
    }

    companion object {
        val NONE = PromptStatus(takeCount = 0, keptCount = 0, latestStatus = null, textChanged = false)
    }
}

/** A prompt as the Prompts screen lists it. [searchText] is normalized once
 * per data change, so a keystroke only runs substring checks. */
data class PromptRow(val prompt: Prompt, val status: PromptStatus, val searchText: String)

data class FilterResult(val rows: List<PromptRow>, val counts: Map<PromptFilter, Int>)

object PromptSearch {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** Lower case without accents, so "Ndebele" finds "ndébélé" and back. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)

    fun statusOf(prompt: Prompt, summary: PromptTakeSummary?): PromptStatus {
        if (summary == null) return PromptStatus.NONE
        val keptVersions = summary.keptVersions.orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        return PromptStatus(
            takeCount = summary.takeCount,
            keptCount = summary.keptCount,
            latestStatus = summary.latest?.substringAfterLast('|'),
            textChanged = summary.keptCount > 0 && prompt.version !in keptVersions,
        )
    }

    /** Text, id, category (with and without underscores) and the distinct
     * transcripts of her takes, which differ from the prompt text once
     * either was edited. */
    fun searchTextOf(prompt: Prompt, summary: PromptTakeSummary?): String {
        val transcripts = summary?.transcripts.orEmpty().lines().distinct().filter { it != prompt.text }
        return normalize(
            (listOf(prompt.text, prompt.id, prompt.category, prompt.category.replace('_', ' ')) + transcripts)
                .joinToString("\n")
        )
    }

    fun buildRows(prompts: List<Prompt>, summaries: Map<String, PromptTakeSummary>): List<PromptRow> =
        prompts.map { p ->
            val summary = summaries[p.id]
            PromptRow(p, statusOf(p, summary), searchTextOf(p, summary))
        }

    /** Every word of the query must appear somewhere in the row. */
    fun queryTerms(query: String): List<String> =
        normalize(query).trim().split(WHITESPACE).filter { it.isNotEmpty() }

    fun matches(row: PromptRow, terms: List<String>): Boolean = terms.all { row.searchText.contains(it) }

    /** Rows matching the search and category, narrowed by [filter], with the
     * count each status chip would show for the same search and category. */
    fun filter(rows: List<PromptRow>, query: String, filter: PromptFilter, category: String?): FilterResult {
        val terms = queryTerms(query)
        val scoped = rows.filter { (category == null || it.prompt.category == category) && matches(it, terms) }
        val counts = PromptFilter.values().associateWith { f -> scoped.count { it.status.matches(f) } }
        return FilterResult(scoped.filter { it.status.matches(filter) }, counts)
    }
}
