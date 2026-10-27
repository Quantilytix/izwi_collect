package com.quantilytix.izwi.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema steps, each one additive, so unsynced clips always survive an app
 * update. The SQL lives in plain lists so MigrationTest can run the same
 * statements against a real SQLite and compare the result with the schema
 * Room exports to app/schemas.
 */
object Migrations {

    // v1 -> v2 added the category column. v1 never held real
    // recordings, but a migration costs one line and leaves no path that
    // drops the database.
    internal val SQL_1_2 = listOf(
        "ALTER TABLE `clips` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'custom'",
    )

    // v2 -> v3 (v0.7.0) records the prompt text version each take was read
    // from. Every earlier take was read from version 1.
    internal val SQL_2_3 = listOf(
        "ALTER TABLE `clips` ADD COLUMN `promptVersion` INTEGER NOT NULL DEFAULT 1",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) = SQL_1_2.forEach(db::execSQL)
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) = SQL_2_3.forEach(db::execSQL)
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
