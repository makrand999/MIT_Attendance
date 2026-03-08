package com.mit.attendance.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.ui.subjects.SubjectsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AttendanceSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME           = "attendance_sync"
        const val WORK_NAME_IMMEDIATE = "attendance_sync_immediate"
        const val CHANNEL_ID          = "attendance_updates"
        const val CHANNEL_NAME        = "Attendance Updates"
        const val UPDATE_CHANNEL_ID   = "app_updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        private const val TAG         = "SyncWorker"

        /** One-shot sync — call after login and on cold app open. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                    .setConstraints(networkConstraints())
                    .addTag(WORK_NAME_IMMEDIATE)
                    .build()
            )
            Log.d(TAG, "Immediate one-time sync enqueued")
        }

        /** Periodic background sync — only runs when notifications are enabled. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AttendanceSyncWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            )
            Log.d(TAG, "Periodic sync scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Periodic sync cancelled")
        }

        fun createNotificationChannels(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val attendanceChannel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifies when new attendance entries are recorded" }
            notificationManager.createNotificationChannel(attendanceChannel)

            val updateChannel = NotificationChannel(
                UPDATE_CHANNEL_ID, UPDATE_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifies when a new app update is available" }
            notificationManager.createNotificationChannel(updateChannel)
        }

        private fun networkConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Background sync started")
                val repo = AttendanceRepository(context)

                if (!repo.prefs.areNotificationsEnabledSnapshot()) {
                    Log.d(TAG, "Notifications disabled — skipping sync")
                    return@withContext Result.success()
                }

                val newCount = repo.backgroundSync()
                Log.d(TAG, "Background sync complete. New entries: $newCount")

                if (newCount > 0) showNotification(newCount)

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Background sync failed", e)
                Result.retry()
            }
        }
    }

    private fun showNotification(newCount: Int) {
        val intent = Intent(context, SubjectsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (newCount == 1) "1 new attendance entry recorded"
        else "$newCount new attendance entries recorded"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MIT Attendance Update")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(System.currentTimeMillis().toInt(), notification)
    }
}

// ── Boot receiver ─────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AttendanceSyncWorker.schedule(context)
        }
    }
}
