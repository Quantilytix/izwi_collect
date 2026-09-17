package com.quantilytix.izwi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "consent_records")
data class ConsentRecordEntity(
    @PrimaryKey val sessionId: String,
    val speakerId: String,
    val consentVersion: String,
    val acceptedAtUtc: String,
    val accepted: Boolean,
    val deviceModel: String,
)
