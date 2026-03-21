package com.mit.attendance

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import com.mit.attendance.data.api.HttpClientHolder
import com.mit.attendance.service.AttendanceSyncWorker

class MITAttendanceApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // Force dark mode globally
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Must be first — everything else depends on the singleton OkHttpClient
        HttpClientHolder.init(this)
        AttendanceSyncWorker.createNotificationChannels(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
}