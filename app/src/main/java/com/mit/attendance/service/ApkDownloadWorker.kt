package com.mit.attendance.service

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.mit.attendance.R
import com.mit.attendance.data.api.HttpClientHolder
import com.mit.attendance.storage.OutputDirectoryManager
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class ApkDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_TAG_NAME = "tag_name"
        const val NOTIFICATION_ID = 999
        
        @Volatile
        private var isDownloading = false
        
        fun isAlreadyDownloading() = isDownloading

        fun enqueue(context: Context, url: String, tagName: String) {
            val data = workDataOf(
                KEY_URL to url,
                KEY_TAG_NAME to tagName
            )
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("apk_download")
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "apk_download_unique",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val tagName = inputData.getString(KEY_TAG_NAME) ?: return Result.failure()
        
        isDownloading = true
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, AttendanceSyncWorker.UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloading Update")
            .setContentText("Version $tagName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, false)

        try {
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    NOTIFICATION_ID, 
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(NOTIFICATION_ID, builder.build())
            }
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Log.e("ApkDownloadWorker", "Failed to set foreground", e)
        }

        return try {
            val versionFolder = OutputDirectoryManager.getVersionFolder(context, tagName)
            val destination = File(versionFolder, "MIT_Attendance.apk")
            val tempFile = File(versionFolder, "MIT_Attendance.apk.tmp")
            
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder().url(url).build()
            val client = HttpClientHolder.client
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Failed to download: ${response.code}")
                
                val body = response.body ?: throw Exception("Empty body")
                val totalBytes = body.contentLength()
                
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastProgressUpdate = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            val currentTime = System.currentTimeMillis()
                            if (totalBytes > 0 && currentTime - lastProgressUpdate > 500) {
                                val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                builder.setProgress(100, progress, false)
                                    .setContentText("Downloading... $progress%")
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                                lastProgressUpdate = currentTime
                            }
                        }
                    }
                }
            }

            if (destination.exists()) destination.delete()
            tempFile.renameTo(destination)

            showFinishedNotification(tagName, true)
            isDownloading = false
            Result.success()
        } catch (e: Exception) {
            Log.e("ApkDownloadWorker", "Download failed", e)
            showFinishedNotification(tagName, false)
            isDownloading = false
            Result.failure()
        }
    }

    private fun showFinishedNotification(tagName: String, success: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, AttendanceSyncWorker.UPDATE_CHANNEL_ID)
            .setSmallIcon(if (success) R.drawable.ic_download else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Update Downloaded" else "Download Failed")
            .setContentText(if (success) "Version $tagName ready to install" else "Failed to download v$tagName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}
