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
    )

    @Test
    fun manifestKeepsLegacyColumnsAndAppendsPromptIdAndCategory() {
        val lines = BatchExporter.buildManifestCsv(listOf(clip)).trimEnd().lines()
        val header = lines[0].split(",")
        assertEquals(legacyColumns + listOf("prompt_id", "category"), header)

        val row = lines[1].split(",")
        assertEquals(header.size, row.size)
        assertEquals("clip_abc", row[0])
        assertEquals("accepted", row[15])
        assertEquals("cue_007", row[16])
        assertEquals("free_form_cue_card", row[17])
    }

    @Test
    fun metadataCarriesPromptIdAndCategory() {
        val obj = JSONObject(BatchExporter.buildMetadataJsonl(listOf(clip)).trimEnd())
        assertEquals("cue_007", obj.getString("prompt_id"))
        assertEquals("free_form_cue_card", obj.getString("category"))
        assertEquals("clip_abc", obj.getString("clip_id"))
    }
}
