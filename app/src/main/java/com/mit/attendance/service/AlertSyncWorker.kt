package com.mit.attendance.service

import android.content.Context
import androidx.work.*
import com.mit.attendance.campus.CampusSessionStore
import com.mit.attendance.campus.CampusSyncWorker

/** Compatibility entry point; legacy unscoped jobs never fetch or announce another account's data. */
class AlertSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = Result.success()
    companion object {
        const val WORK_NAME="alert_sync"
        const val WORK_NAME_IMMEDIATE="alert_sync_immediate"
        const val CHANNEL_ID="class_alerts"
        fun runNow(context:Context) { CampusSessionStore(context).read()?.let{CampusSyncWorker.enqueue(context,it)} }
        fun schedule(context:Context) { cancel(context);runNow(context) }
        fun cancel(context:Context) { WorkManager.getInstance(context).apply {cancelUniqueWork(WORK_NAME);cancelUniqueWork(WORK_NAME_IMMEDIATE)} }
    }
}
