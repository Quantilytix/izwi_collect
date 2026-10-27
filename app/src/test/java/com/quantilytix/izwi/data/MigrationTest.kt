package com.quantilytix.izwi.data

import com.quantilytix.izwi.session.Prompt
import com.quantilytix.izwi.session.PromptSearch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the Room migrations against a real SQLite (sqlite-jdbc) and checks
 * the result against the schema Room exports to app/schemas, the same
 * column checks Room makes when it opens a migrated database. Also runs the
 * Prompts screen's grouped query on real rows.
 */
class MigrationTest {

    private val schemaDir = File("schemas/com.quantilytix.izwi.data.AppDatabase")

    private fun schema(version: Int) = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")

    private fun createStatements(version: Int): List<String> {
        val entities = schema(version).getJSONArray("entities")
        return (0 until entities.length()).map { i ->
            val e = entities.getJSONObject(i)
            e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
        }
    }

    /** Columns whose entity field declares a default; Room compares the
     * database default only for those. */
    private fun entityDefaults(version: Int, table: String): Set<String> {
        val entities = schema(version).getJSONArray("entities")
        val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it) }
            .filter { it.has("defaultValue") }
            .map { it.getString("columnName") }
            .toSet()
    }

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private data class Column(val type: String, val notNull: Boolean, val pk: Int, val default: String?)

    private fun Connection.columns(table: String, compareDefaults: Set<String>): Map<String, Column> =
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                val out = mutableMapOf<String, Column>()
                while (rs.next()) {
                    val name = rs.getString("name")
                    out[name] = Column(
                        type = rs.getString("type"),
                        notNull = rs.getInt("notnull") == 1,
                        pk = rs.getInt("pk"),
                        default = if (name in compareDefaults) rs.getString("dflt_value") else null,
                    )
                }
                out
            }
        }

    private fun assertMatchesV3(db: Connection) {
        open().use { fresh ->
            createStatements(3).forEach { fresh.exec(it) }
            for (table in listOf("clips", "consent_records", "batches")) {
                val defaults = entityDefaults(3, table)
                assertEquals("table $table", fresh.columns(table, defaults), db.columns(table, defaults))
            }
        }
    }

    // An unsynced, accepted v0.6.0 take: batchId null, uploadStatus pending.
    private val v060ClipInsert =
        "INSERT INTO clips (clipId, speakerId, sessionId, promptId, category, language, transcript, sampleRate, " +
            "durationSeconds, qualityScore, snrProxy, speechRatio, clippingDetected, recordedAtUtc, deviceModel, " +
            "consentVersion, scriptVersion, reviewStatus, skipReason, filePath, batchId, uploadStatus) VALUES " +
            "('clip_old', 'spk', 'session_1', 'free_form_cue_0001', 'free_form_cue', 'sn', 'Prompt text one', 24000, " +
            "64.5, 0.85, 21.0, 0.7, 0, '2026-10-26T09:00:00Z', 'Tablet', 'v1', 'custom', 'accepted', NULL, " +
            "'/data/audio/clip_old.wav', NULL, 'pending')"

    @Test
    fun v2ToV3KeepsUnsyncedClipsAndStartsThemAtPromptVersionOne() {
        open().use { db ->
            createStatements(2).forEach { db.exec(it) }
            db.exec(v060ClipInsert)

            Migrations.SQL_2_3.forEach { db.exec(it) }

            db.createStatement().use { st ->
                st.executeQuery("SELECT * FROM clips").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("clip_old", rs.getString("clipId"))
                    assertEquals("Prompt text one", rs.getString("transcript"))
                    assertEquals("accepted", rs.getString("reviewStatus"))
                    assertEquals("/data/audio/clip_old.wav", rs.getString("filePath"))
                    assertNull(rs.getString("batchId"))
                    assertEquals("pending", rs.getString("uploadStatus"))
                    assertEquals(1, rs.getInt("promptVersion"))
                    assertFalse(rs.next())
                }
            }
            assertMatchesV3(db)
        }
    }

    @Test
    fun v1ToV3RunsEveryStepWithoutDroppingRows() {
        open().use { db ->
            // v1 is v2 without the category column.
            createStatements(2).forEach { db.exec(it.replace("`category` TEXT NOT NULL, ", "")) }
            db.exec(v060ClipInsert.replace("category, ", "").replace("'free_form_cue', ", ""))

            (Migrations.SQL_1_2 + Migrations.SQL_2_3).forEach { db.exec(it) }

            db.createStatement().use { st ->
                st.executeQuery("SELECT category, promptVersion FROM clips WHERE clipId = 'clip_old'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("custom", rs.getString(1))
                    assertEquals(1, rs.getInt(2))
                }
            }
            assertMatchesV3(db)
        }
    }

    private fun Connection.insertTake(
        clipId: String, promptId: String, status: String, at: String,
        version: Int = 1, speaker: String = "spk", language: String = "sn", transcript: String = "Text of $promptId",
    ) {
        prepareStatement(
            "INSERT INTO clips (clipId, speakerId, sessionId, promptId, category, language, transcript, sampleRate, " +
                "durationSeconds, qualityScore, snrProxy, speechRatio, clippingDetected, recordedAtUtc, deviceModel, " +
                "consentVersion, scriptVersion, reviewStatus, uploadStatus, promptVersion) " +
                "VALUES (?, ?, 's1', ?, 'cat', ?, ?, 24000, 5.0, 0.9, 20.0, 0.7, 0, ?, 'Tablet', 'v1', 'v1', ?, 'pending', ?)"
        ).use { ps ->
            ps.setString(1, clipId)
            ps.setString(2, speaker)
            ps.setString(3, promptId)
            ps.setString(4, language)
            ps.setString(5, transcript)
            ps.setString(6, at)
            ps.setString(7, status)
            ps.setInt(8, version)
            ps.executeUpdate()
        }
    }

    private fun Connection.summaries(speaker: String, language: String): Map<String, PromptTakeSummary> {
        // Room binds :speakerId then :language; SQLite numbers named
        // parameters in order of first appearance, so these indexes match.
        prepareStatement(PROMPT_SUMMARY_QUERY).use { ps ->
            ps.setString(1, speaker)
            ps.setString(2, language)
            ps.executeQuery().use { rs ->
                val out = mutableMapOf<String, PromptTakeSummary>()
                while (rs.next()) {
                    val s = PromptTakeSummary(
                        promptId = rs.getString("promptId"),
                        takeCount = rs.getInt("takeCount"),
                        keptCount = rs.getInt("keptCount"),
                        keptVersions = rs.getString("keptVersions"),
                        latest = rs.getString("latest"),
                        transcripts = rs.getString("transcripts"),
                    )
                    out[s.promptId] = s
                }
                return out
            }
        }
    }

    @Test
    fun promptSummaryQueryGivesStatusForEveryPromptInOneQuery() {
        open().use { db ->
            createStatements(3).forEach { db.exec(it) }
            // a: accepted, then a later take marked retake
            db.insertTake("c1", "a", "accepted", "2026-10-27T09:00:00Z")
            db.insertTake("c2", "a", "retake", "2026-10-27T09:05:00Z")
            // b: skipped only
            db.insertTake("c3", "b", "skipped", "2026-10-27T09:01:00Z")
            // c: a warning take read from version 2
            db.insertTake("c4", "c", "warning", "2026-10-27T09:02:00Z", version = 2, transcript = "Edited text")
            // d: accepted from version 1, then a new accepted take from version 2
            db.insertTake("c5", "d", "accepted", "2026-10-27T09:03:00Z", version = 1)
            db.insertTake("c6", "d", "accepted", "2026-10-27T09:04:00Z", version = 2)
            // another speaker and another language never leak in
            db.insertTake("c7", "e", "accepted", "2026-10-27T09:00:00Z", speaker = "other")
            db.insertTake("c8", "nd_e", "accepted", "2026-10-27T09:00:00Z", language = "nd")

            val s = db.summaries("spk", "sn")
            assertEquals(setOf("a", "b", "c", "d"), s.keys)

            val a = PromptSearch.statusOf(Prompt("a", "cat", "Text of a"), s["a"])
            assertEquals(2, a.takeCount)
            assertTrue(a.isRecorded)
            assertTrue(a.needsRetake)

            val b = PromptSearch.statusOf(Prompt("b", "cat", "Text of b"), s["b"])
            assertEquals(0, b.takeCount)
            assertTrue(b.isOutstanding)
            assertTrue(b.needsRetake)

            val c = PromptSearch.statusOf(Prompt("c", "cat", "Edited text", version = 2), s["c"])
            assertTrue(c.isRecorded)
            assertTrue(c.needsRetake)
            assertFalse(c.textChanged)
            assertTrue(PromptSearch.statusOf(Prompt("c", "cat", "Newer text", version = 3), s["c"]).textChanged)
            assertTrue(PromptSearch.searchTextOf(Prompt("c", "cat", "Newer text", version = 3), s["c"]).contains("edited text"))

            assertEquals("1,2", s["d"]!!.keptVersions!!.split(',').sorted().joinToString(","))
            assertFalse(PromptSearch.statusOf(Prompt("d", "cat", "Text of d", version = 2), s["d"]).textChanged)

            assertEquals(setOf("nd_e"), db.summaries("spk", "nd").keys)
        }
    }
}
