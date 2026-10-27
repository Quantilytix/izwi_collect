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

    /** Earlier usable takes of one prompt in this session that no upload
     * batch has claimed yet, so a redo after Back can still replace them. */
    @Query(
        "SELECT * FROM clips WHERE sessionId = :sessionId AND promptId = :promptId " +
            "AND reviewStatus IN ('accepted', 'warning') AND batchId IS NULL"
    )
    suspend fun getUnbatchedTakesForPrompt(sessionId: String, promptId: String): List<ClipEntity>

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

    /** One row per prompt this speaker has any take of in [language], across
     * every session. The Prompts screen reads status, take count and search
     * text for all 6,106 prompts from this single grouped query. */
    @Query(PROMPT_SUMMARY_QUERY)
    fun observePromptSummaries(speakerId: String, language: String): Flow<List<PromptTakeSummary>>
}

data class CategoryCount(val category: String, val count: Int)

/**
 * [latest] is the newest take's recordedAtUtc and review status joined by
 * "|". recordedAtUtc is fixed-width ISO UTC, so the string MAX is the newest
 * take, without a second query or window functions (SQLite on API 26 has
 * none). [keptVersions] lists the distinct prompt versions of accepted and
 * warning takes, comma separated. [transcripts] joins every take's
 * transcript with newlines, for search.
 */
data class PromptTakeSummary(
    val promptId: String,
    val takeCount: Int,
    val keptCount: Int,
    val keptVersions: String?,
    val latest: String?,
    val transcripts: String?,
)

// A constant so MigrationTest can run the exact SQL against a real SQLite.
internal const val PROMPT_SUMMARY_QUERY =
    "SELECT promptId, " +
        "SUM(CASE WHEN reviewStatus != 'skipped' THEN 1 ELSE 0 END) AS takeCount, " +
        "SUM(CASE WHEN reviewStatus IN ('accepted', 'warning') THEN 1 ELSE 0 END) AS keptCount, " +
        "GROUP_CONCAT(DISTINCT CASE WHEN reviewStatus IN ('accepted', 'warning') THEN promptVersion END) AS keptVersions, " +
        "MAX(recordedAtUtc || '|' || reviewStatus) AS latest, " +
        "GROUP_CONCAT(transcript, char(10)) AS transcripts " +
        "FROM clips WHERE speakerId = :speakerId AND language = :language " +
        "GROUP BY promptId"
