package com.quantilytix.izwi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(batch: BatchEntity)

    @Update
    suspend fun update(batch: BatchEntity)

    @Query("SELECT * FROM batches WHERE sessionId = :sessionId ORDER BY createdAtUtc DESC")
    fun observeForSession(sessionId: String): Flow<List<BatchEntity>>

    @Query("SELECT * FROM batches WHERE batchId = :batchId LIMIT 1")
    suspend fun get(batchId: String): BatchEntity?

    @Query("SELECT * FROM batches WHERE uploadStatus != 'uploaded' ORDER BY createdAtUtc ASC")
    suspend fun getPending(): List<BatchEntity>
}
