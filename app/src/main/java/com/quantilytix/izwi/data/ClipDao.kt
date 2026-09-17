package com.quantilytix.izwi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(clip: ClipEntity)

    @Update
    suspend fun update(clip: ClipEntity)

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY recordedAtUtc ASC")
    fun observeForSession(sessionId: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY recordedAtUtc ASC")
    suspend fun getForSession(sessionId: String): List<ClipEntity>

    @Query(
        "SELECT * FROM clips WHERE sessionId = :sessionId " +
            "AND reviewStatus IN ('accepted', 'warning') AND batchId IS NULL " +
            "ORDER BY recordedAtUtc ASC"
    )
    suspend fun getUnbatchedApproved(sessionId: String): List<ClipEntity>

    @Query("UPDATE clips SET batchId = :batchId, uploadStatus = :status WHERE clipId IN (:clipIds)")
    suspend fun assignBatch(clipIds: List<String>, batchId: String, status: String)

    @Query("UPDATE clips SET uploadStatus = :status WHERE batchId = :batchId")
    suspend fun setUploadStatusForBatch(batchId: String, status: String)

    @Query("SELECT COUNT(*) FROM clips WHERE sessionId = :sessionId AND promptId = :promptId AND reviewStatus != 'retake'")
    suspend fun countAcceptedForPrompt(sessionId: String, promptId: String): Int

    /** Cumulative usable audio for this speaker across every session, not
     * just the one in progress — this is what the 2h-baseline / 5h meter on
     * the review screen tracks. */
    @Query("SELECT COALESCE(SUM(durationSeconds), 0.0) FROM clips WHERE speakerId = :speakerId AND reviewStatus IN ('accepted', 'warning')")
    fun observeTotalDurationForSpeaker(speakerId: String): Flow<Double>

    /** Recorded-clip counts per script category for this speaker, across
     * every session — the script screen uses this against each category's
     * prompt count to surface which category still needs the most takes. */
    @Query(
        "SELECT category, COUNT(*) as count FROM clips " +
            "WHERE speakerId = :speakerId AND reviewStatus IN ('accepted', 'warning') " +
            "GROUP BY category"
    )
    fun observeCategoryCountsForSpeaker(speakerId: String): Flow<List<CategoryCount>>
}

data class CategoryCount(val category: String, val count: Int)
