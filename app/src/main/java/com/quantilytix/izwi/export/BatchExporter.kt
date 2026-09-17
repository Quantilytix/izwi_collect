package com.quantilytix.izwi.export

import android.content.Context
import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ConsentRecordEntity
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val MANIFEST_HEADER = listOf(
    "clip_id", "speaker_id", "session_id", "language", "transcript", "sample_rate",
    "duration_seconds", "quality_score", "snr_proxy", "speech_ratio", "clipping_detected",
    "recorded_at_utc", "device_model", "consent_version", "script_version", "review_status",
)

/** Bounded upload batches of ~20-30 minutes, per PLAN_B Phase 5 — a full raw
 * two-hour session is too large for the relay's upload ceiling. */
object BatchChunker {
    private const val TARGET_BATCH_SECONDS = 25 * 60.0

    fun chunkByDuration(clips: List<ClipEntity>): List<List<ClipEntity>> {
        val batches = mutableListOf<MutableList<ClipEntity>>()
        var current = mutableListOf<ClipEntity>()
        var currentSeconds = 0.0
        for (clip in clips) {
            if (currentSeconds + clip.durationSeconds > TARGET_BATCH_SECONDS && current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                currentSeconds = 0.0
            }
            current.add(clip)
            currentSeconds += clip.durationSeconds
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}

object BatchExporter {

    /** Excludes everything not part of the voice corpus contract — no
     * device/app data, no engine-monitoring artifacts, matching PLAN_B's
     * "do not store unrelated app data" requirement. */
    fun buildBatchZip(context: Context, clips: List<ClipEntity>, consent: ConsentRecordEntity): File {
        val batchId = "batch_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val outDir = File(context.cacheDir, "batches").apply { mkdirs() }
        val zipFile = File(outDir, "$batchId.zip")

        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.csv"))
            zip.write(buildManifestCsv(clips).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("metadata.jsonl"))
            zip.write(buildMetadataJsonl(clips).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("consent.json"))
            zip.write(buildConsentJson(consent).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (clip in clips) {
                val path = clip.filePath ?: continue
                val audioFile = File(path)
                if (!audioFile.exists()) continue
                zip.putNextEntry(ZipEntry("audio/${clip.clipId}.wav"))
                audioFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    private fun buildManifestCsv(clips: List<ClipEntity>): String {
        val sb = StringBuilder()
        sb.append(MANIFEST_HEADER.joinToString(",")).append("\n")
        for (c in clips) {
            val row = listOf(
                c.clipId, c.speakerId, c.sessionId, c.language, c.transcript,
                c.sampleRate.toString(), c.durationSeconds.toString(), c.qualityScore.toString(),
                c.snrProxy.toString(), c.speechRatio.toString(), c.clippingDetected.toString(),
                c.recordedAtUtc, c.deviceModel, c.consentVersion, c.scriptVersion, c.reviewStatus,
            )
            sb.append(row.joinToString(",") { csvEscape(it) }).append("\n")
        }
        return sb.toString()
    }

    private fun buildMetadataJsonl(clips: List<ClipEntity>): String {
        val sb = StringBuilder()
        for (c in clips) {
            val obj = JSONObject()
            obj.put("clip_id", c.clipId)
            obj.put("speaker_id", c.speakerId)
            obj.put("session_id", c.sessionId)
            obj.put("language", c.language)
            obj.put("transcript", c.transcript)
            obj.put("sample_rate", c.sampleRate)
            obj.put("duration_seconds", c.durationSeconds)
            obj.put("quality_score", c.qualityScore)
            obj.put("snr_proxy", c.snrProxy)
            obj.put("speech_ratio", c.speechRatio)
            obj.put("clipping_detected", c.clippingDetected)
            obj.put("recorded_at_utc", c.recordedAtUtc)
            obj.put("device_model", c.deviceModel)
            obj.put("consent_version", c.consentVersion)
            obj.put("script_version", c.scriptVersion)
            obj.put("review_status", c.reviewStatus)
            sb.append(obj.toString()).append("\n")
        }
        return sb.toString()
    }

    private fun buildConsentJson(consent: ConsentRecordEntity): String {
        val obj = JSONObject()
        obj.put("consent_version", consent.consentVersion)
        obj.put("speaker_id", consent.speakerId)
        obj.put("session_id", consent.sessionId)
        obj.put("accepted", consent.accepted)
        obj.put("timestamp", consent.acceptedAtUtc)
        obj.put("device_model", consent.deviceModel)
        return obj.toString(2)
    }
}
