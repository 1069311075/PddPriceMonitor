package com.example.pddpricemonitor

import android.app.Application
import com.example.pddpricemonitor.data.AppDatabase

class PddMonitorApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
}
