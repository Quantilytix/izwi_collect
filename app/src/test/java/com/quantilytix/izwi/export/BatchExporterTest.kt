package com.quantilytix.izwi.export

import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ReviewStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchExporterTest {

    // The columns the relay contract had before v0.6.0, in their order.
    private val legacyColumns = listOf(
        "clip_id", "speaker_id", "session_id", "language", "transcript", "sample_rate",
        "duration_seconds", "quality_score", "snr_proxy", "speech_ratio", "clipping_detected",
        "recorded_at_utc", "device_model", "consent_version", "script_version", "review_status",
    )

    private val clip = ClipEntity(
        clipId = "clip_abc",
        speakerId = "spk",
        sessionId = "session_1",
        promptId = "cue_007",
        category = "free_form_cue_card",
        language = "sn",
        transcript = "Mhoro",
        sampleRate = 24000,
        durationSeconds = 64.5,
        qualityScore = 0.85,
        snrProxy = 21.0,
        speechRatio = 0.7,
        clippingDetected = false,
        recordedAtUtc = "2026-10-26T09:00:00Z",
        deviceModel = "Test Tablet",
        consentVersion = "v1",
        scriptVersion = "v3",
        reviewStatus = ReviewStatus.ACCEPTED,
        promptVersion = 2,
    )

    @Test
    fun manifestKeepsEarlierColumnsAndAppendsPromptVersionLast() {
        val lines = BatchExporter.buildManifestCsv(listOf(clip)).trimEnd().lines()
        val header = lines[0].split(",")
        assertEquals(legacyColumns + listOf("prompt_id", "category", "prompt_version"), header)

        val row = lines[1].split(",")
        assertEquals(header.size, row.size)
        assertEquals("clip_abc", row[0])
        assertEquals("accepted", row[15])
        assertEquals("cue_007", row[16])
        assertEquals("free_form_cue_card", row[17])
        assertEquals("2", row[18])
    }

    @Test
    fun metadataCarriesPromptIdCategoryAndVersion() {
        val obj = JSONObject(BatchExporter.buildMetadataJsonl(listOf(clip)).trimEnd())
        assertEquals("cue_007", obj.getString("prompt_id"))
        assertEquals("free_form_cue_card", obj.getString("category"))
        assertEquals(2, obj.getInt("prompt_version"))
        assertEquals("clip_abc", obj.getString("clip_id"))
    }

    @Test
    fun takesFromBeforeV070ExportPromptVersionOne() {
        val old = clip.copy(promptVersion = 1)
        assertEquals("1", BatchExporter.buildManifestCsv(listOf(old)).trimEnd().lines()[1].split(",").last())
    }

    @Test
    fun aBatchNeverMixesLanguages() {
        val sn1 = clip.copy(clipId = "sn1", language = "sn", durationSeconds = 600.0)
        val nd1 = clip.copy(clipId = "nd1", language = "nd", durationSeconds = 600.0)
        val sn2 = clip.copy(clipId = "sn2", language = "sn", durationSeconds = 600.0)
        val sn3 = clip.copy(clipId = "sn3", language = "sn", durationSeconds = 600.0)
        val nd2 = clip.copy(clipId = "nd2", language = "nd", durationSeconds = 60.0)

        val batches = BatchChunker.chunkByLanguageAndDuration(listOf(sn1, nd1, sn2, sn3, nd2))
        for (batch in batches) assertEquals(1, batch.map { it.language }.toSet().size)
        // Shona first (it appears first), still bounded at about 25 minutes.
        assertEquals(listOf(listOf("sn1", "sn2"), listOf("sn3"), listOf("nd1", "nd2")), batches.map { b -> b.map { it.clipId } })
    }
}
