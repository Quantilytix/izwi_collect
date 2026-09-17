package com.quantilytix.izwi.data

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
    /** Not part of the relay's manifest.csv contract — app-internal only,
     * used to break down recording progress per script category. */
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
)
