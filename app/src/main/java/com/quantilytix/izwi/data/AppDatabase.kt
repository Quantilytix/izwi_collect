package com.quantilytix.izwi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ClipEntity::class, ConsentRecordEntity::class, BatchEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun consentDao(): ConsentDao
    abstract fun batchDao(): BatchDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "izwi.db")
                // Real, unsynced recordings live here since v0.6.0, so every
                // schema change ships a migration and there is no destructive
                // fallback: a missing migration fails loudly instead of
                // deleting takes.
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
