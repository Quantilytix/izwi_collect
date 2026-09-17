package com.quantilytix.izwi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ClipEntity::class, ConsentRecordEntity::class, BatchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun consentDao(): ConsentDao
    abstract fun batchDao(): BatchDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "izwi.db")
                .build()
    }
}
