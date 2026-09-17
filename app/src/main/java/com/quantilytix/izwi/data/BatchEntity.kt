package com.quantilytix.izwi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "batches")
data class BatchEntity(
    @PrimaryKey val batchId: String,
    val sessionId: String,
    val speakerId: String,
    val createdAtUtc: String,
    val clipCount: Int,
    val zipPath: String,
    val uploadStatus: String = UploadStatus.PENDING,
    val prUrl: String? = null,
    val errorMessage: String? = null,
    val lastAttemptAtUtc: String? = null,
)
