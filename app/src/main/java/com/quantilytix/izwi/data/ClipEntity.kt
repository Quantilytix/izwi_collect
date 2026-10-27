package com.quantilytix.izwi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Review status of a single recorded utterance. Server-side terminology
 * mirrors this exactly (see `review_status` in the shared manifest contract). */
object ReviewStatus {
    const val ACCEPTED = "accepted"
    const val WARNING = "warning"
    const val RETAKE = "retake"
    const val SKIPPED = "skipped"
}

object UploadStatus {
    const val PENDING = "pending"
    const val QUEUED = "queued"
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"
}

/**
 * One recorded (or skipped) utterance. Field names match the session
 * manifest contract in PLAN_B_officer_voice_capture_app.md exactly, since
 * this row is serialized almost verbatim into manifest.csv for the relay.
 */
@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey val clipId: String,
    val speakerId: String,
    val sessionId: String,
    val promptId: String,
    /** Breaks down recording progress per script category. Since v0.6.0 it
     * is also exported, after promptId, as a manifest.csv column. */
    val category: String,
    val language: String,
    val transcript: String,
    val sampleRate: Int,
    val durationSeconds: Double,
    val qualityScore: Double,
    val snrProxy: Double,
    val speechRatio: Double,
    val clippingDetected: Boolean,
    val recordedAtUtc: String,
    val deviceModel: String,
    val consentVersion: String,
    val scriptVersion: String,
    val reviewStatus: String,
    val skipReason: String? = null,
    val filePath: String? = null,
    val batchId: String? = null,
    val uploadStatus: String = UploadStatus.PENDING,
    /** The prompt's text version when this take was recorded (v0.7.0). An
     * edit bumps the prompt's version, so a take whose version differs from
     * the prompt's current one was read from older text. Rows from before
     * v0.7.0 migrate to 1, the version every prompt starts at. */
    @ColumnInfo(defaultValue = "1") val promptVersion: Int = 1,
)
