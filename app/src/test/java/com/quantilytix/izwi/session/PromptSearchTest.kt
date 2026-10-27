package com.quantilytix.izwi.session

import com.quantilytix.izwi.data.PromptTakeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSearchTest {

    private fun summary(
        id: String, takes: Int, kept: Int, latest: String?, keptVersions: String? = null, transcripts: String? = null,
    ) = PromptTakeSummary(id, takes, kept, keptVersions, latest?.let { "2026-10-27T09:00:00Z|$it" }, transcripts)

    private fun row(prompt: Prompt, summary: PromptTakeSummary? = null) =
        PromptRow(prompt, PromptSearch.statusOf(prompt, summary), PromptSearch.searchTextOf(prompt, summary))

    private fun matches(row: PromptRow, query: String) = PromptSearch.matches(row, PromptSearch.queryTerms(query))

    @Test
    fun normalizeDropsCaseAndAccents() {
        assertEquals("cafe resume naive", PromptSearch.normalize("Café RÉSUMÉ naïve"))
    }

    @Test
    fun searchMatchesTextIdCategoryAndTakeTranscripts() {
        val p = Prompt("stock_inventory_0042", "stock_inventory", "Three crates arrived today.")
        val r = row(p, summary(p.id, 1, 1, "accepted", "1", transcripts = "Two crates came in this morning."))

        assertTrue(matches(r, "CRATES"))
        assertTrue(matches(r, "0042"))
        assertTrue(matches(r, "stock_inventory"))
        assertTrue(matches(r, "stock inventory"))
        assertTrue(matches(r, "morning"))
        assertFalse(matches(r, "invoice"))
    }

    @Test
    fun searchIsAccentInsensitiveBothWays() {
        val r = row(Prompt("p1", "custom", "Meet at the café."))
        assertTrue(matches(r, "cafe"))
        assertTrue(matches(r, "CAFÉ"))
        val plain = row(Prompt("p2", "custom", "Meet at the cafe."))
        assertTrue(matches(plain, "café"))
    }

    @Test
    fun everyWordOfTheQueryMustMatch() {
        val r = row(Prompt("p1", "money", "Sugar is 2 kg for five dollars."))
        assertTrue(matches(r, "sugar dollars"))
        assertTrue(matches(r, "  sugar   money "))
        assertFalse(matches(r, "sugar bread"))
        assertTrue(matches(r, ""))
    }

    @Test
    fun neverRecordedIsOutstandingOnly() {
        val s = PromptSearch.statusOf(Prompt("p", "c", "t"), null)
        assertTrue(s.isOutstanding)
        assertFalse(s.isRecorded)
        assertFalse(s.needsRetake)
        assertEquals(0, s.takeCount)
    }

    @Test
    fun onlyRetakeOrSkippedTakesAreOutstandingAndNeedRetake() {
        val retake = PromptSearch.statusOf(Prompt("p", "c", "t"), summary("p", 1, 0, "retake"))
        assertTrue(retake.isOutstanding)
        assertTrue(retake.needsRetake)

        val skipped = PromptSearch.statusOf(Prompt("p", "c", "t"), summary("p", 0, 0, "skipped"))
        assertTrue(skipped.isOutstanding)
        assertTrue(skipped.needsRetake)
    }

    @Test
    fun acceptedIsRecordedAndDone() {
        val s = PromptSearch.statusOf(Prompt("p", "c", "t"), summary("p", 1, 1, "accepted", "1"))
        assertTrue(s.isRecorded)
        assertFalse(s.isOutstanding)
        assertFalse(s.needsRetake)
    }

    @Test
    fun warningOrLaterRetakeIsRecordedAndNeedsRetake() {
        val warning = PromptSearch.statusOf(Prompt("p", "c", "t"), summary("p", 1, 1, "warning", "1"))
        assertTrue(warning.isRecorded)
        assertTrue(warning.needsRetake)

        val acceptedThenRetake = PromptSearch.statusOf(Prompt("p", "c", "t"), summary("p", 2, 1, "retake", "1"))
        assertTrue(acceptedThenRetake.isRecorded)
        assertTrue(acceptedThenRetake.needsRetake)
    }

    @Test
    fun textChangedWhenNoKeptTakeMatchesTheCurrentVersion() {
        val v3 = Prompt("p", "c", "t", version = 3)
        assertTrue(PromptSearch.statusOf(v3, summary("p", 1, 1, "accepted", "1")).textChanged)
        assertTrue(PromptSearch.statusOf(v3, summary("p", 2, 2, "accepted", "1,2")).textChanged)
        assertFalse(PromptSearch.statusOf(v3, summary("p", 2, 2, "accepted", "1,3")).textChanged)
        // A retake alone is not a kept take, so there is nothing to flag.
        assertFalse(PromptSearch.statusOf(v3, summary("p", 1, 0, "retake")).textChanged)
        // Still counts as recorded.
        assertTrue(PromptSearch.statusOf(v3, summary("p", 1, 1, "accepted", "1")).isRecorded)
    }

    @Test
    fun filterAppliesSearchCategoryAndStatusAndCountsEachChip() {
        val rows = listOf(
            row(Prompt("a", "stock", "Crates of soap")),
            row(Prompt("b", "stock", "Crates of rice"), summary("b", 1, 1, "accepted", "1")),
            row(Prompt("c", "stock", "Crates of oil"), summary("c", 1, 1, "warning", "1")),
            row(Prompt("d", "money", "Crates cost more"), summary("d", 0, 0, "skipped")),
        )

        val outstanding = PromptSearch.filter(rows, "crates", PromptFilter.OUTSTANDING, null)
        assertEquals(listOf("a", "d"), outstanding.rows.map { it.prompt.id })
        assertEquals(4, outstanding.counts[PromptFilter.ALL])
        assertEquals(2, outstanding.counts[PromptFilter.OUTSTANDING])
        assertEquals(2, outstanding.counts[PromptFilter.RECORDED])
        assertEquals(2, outstanding.counts[PromptFilter.NEEDS_RETAKE])

        val stockRetakes = PromptSearch.filter(rows, "", PromptFilter.NEEDS_RETAKE, "stock")
        assertEquals(listOf("c"), stockRetakes.rows.map { it.prompt.id })
        assertEquals(3, stockRetakes.counts[PromptFilter.ALL])

        assertEquals(listOf("b"), PromptSearch.filter(rows, "RICE", PromptFilter.ALL, null).rows.map { it.prompt.id })
    }
}
