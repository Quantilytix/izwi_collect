package com.quantilytix.izwi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ClipEntity::class, ConsentRecordEntity::class, BatchEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun consentDao(): ConsentDao
    abstract fun batchDao(): BatchDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "izwi.db")
                // No real recordings have shipped from this schema yet (v1 -> v2
                // adds ClipEntity.category); destructive fallback is the right
                // call now, not a migration path to maintain forever.
                .fallbackToDestructiveMigration()
                .build()
    }
}
