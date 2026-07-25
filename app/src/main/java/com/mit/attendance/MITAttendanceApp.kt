package com.mit.attendance

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import com.mit.attendance.data.api.HttpClientHolder
import com.mit.attendance.service.AgentSshService
import com.mit.attendance.service.AttendanceSyncWorker
import com.mit.attendance.service.AlertSyncWorker

class MITAttendanceApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // Force dark mode globally
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Must be first — everything else depends on the singleton OkHttpClient
        HttpClientHolder.init(this)
        AttendanceSyncWorker.createNotificationChannels(this)
        createAlertNotificationChannel()
        AgentSshService.createNotificationChannel(this)

        // Initialize Global Cookie Manager for WebViews to ensure persistence across sessions
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // Ensure cookies are persisted to disk
        cookieManager.flush()
    }

    private fun createAlertNotificationChannel() {
        val name = "Class Alerts"
        val descriptionText = "Notifications for test dates, homework, etc."
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(AlertSyncWorker.CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
}
