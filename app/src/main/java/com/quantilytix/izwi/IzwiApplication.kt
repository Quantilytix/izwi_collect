package com.quantilytix.izwi

import android.app.Application
import com.quantilytix.izwi.data.AppDatabase

class IzwiApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
    }
}
