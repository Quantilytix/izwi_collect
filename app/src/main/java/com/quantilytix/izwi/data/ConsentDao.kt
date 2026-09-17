package com.quantilytix.izwi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConsentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ConsentRecordEntity)

    @Query("SELECT * FROM consent_records WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getForSession(sessionId: String): ConsentRecordEntity?
}
