package com.mit.attendance.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.mit.attendance.R
import com.mit.attendance.storage.OutputDirectoryManager
import com.mit.attendance.ui.subjects.UpdateInstallActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ApkDownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_URL = "url"
        const val KEY_TAG_NAME = "tag_name"
        const val NOTIFICATION_ID = 999
        private const val WORK_NAME = "apk_download_unique"
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun enqueue(context: Context, url: String, tagName: String, size: Long = 0, digest: String? = null) {
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(workDataOf(KEY_URL to url, KEY_TAG_NAME to tagName,
                    "size" to size, "digest" to digest))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("apk_download").build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val tag = inputData.getString(KEY_TAG_NAME) ?: return@withContext Result.failure()
        if (!tag.matches(Regex("v[0-9]+")) ||
            !url.startsWith("https://github.com/makrand999/MIT_Attendance/releases/download/")) {
            return@withContext Result.failure()
        }
        val expectedSize = inputData.getLong("size", 0)
        val digest = inputData.getString("digest")
        val folder = OutputDirectoryManager.getVersionFolder(context, tag)
        val destination = File(folder, "MIT_Attendance.apk")
        val partial = File(folder, "MIT_Attendance.apk.tmp")
        val builder = NotificationCompat.Builder(context, AttendanceSyncWorker.UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download).setContentTitle("Downloading update $tag")
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(id))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val notification = builder.build()
            setForeground(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(NOTIFICATION_ID, notification))

            // Resume only when SHA-256 can verify the complete assembled file.
            val offset = if (digest?.startsWith("sha256:") == true) partial.length() else 0L
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    partial.delete()
                    throw IOException("Partial download needs restarting")
                }
                if (response.code == 429 || response.code >= 500) throw IOException("Server temporarily unavailable")
                require(response.isSuccessful) { "Download failed: ${response.code}" }
                require(response.request.url.isHttps) { "Insecure download redirect" }
                val body = response.body ?: throw IOException("Empty download")
                val append = offset > 0 && response.code == 206
                if (response.code == 206) {
                    require(append && response.header("Content-Range")?.startsWith("bytes $offset-") == true) {
                        "Invalid partial download response"
                    }
                }
                var downloaded = if (append) offset else 0L
                val total = if (expectedSize > 0) expectedSize else
                    body.contentLength().takeIf { it >= 0 }?.plus(downloaded) ?: -1L
                var lastUpdate = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            require(expectedSize <= 0 || downloaded <= expectedSize) { "Unexpected APK size" }
                            if (System.currentTimeMillis() - lastUpdate >= 1000) {
                                val progress = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
                                builder.setProgress(100, progress, total <= 0)
                                manager.notify(NOTIFICATION_ID, builder.build())
                                setProgress(workDataOf("bytes" to downloaded, "total" to total))
                                lastUpdate = System.currentTimeMillis()
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total >= 0 && downloaded != total) throw IOException("Incomplete APK download")
            }
            if (digest != null) {
                require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "Unsupported APK digest" }
                val hash = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(digest.substringAfter(':'), ignoreCase = true)) {
                    partial.delete()
                    throw IOException("APK checksum mismatch; restarting download")
                }
            }
            UpdateInstallActivity.validateApk(context, partial, tag.removePrefix("v").toLong())
            check(partial.renameTo(destination)) { "Could not finalize APK download" }
            val pending = PendingIntent.getActivity(context, 0,
                Intent(context, UpdateInstallActivity::class.java)
                    .putExtra(UpdateInstallActivity.EXTRA_APK_PATH, destination.absolutePath),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // WorkManager removes its foreground notification on completion; use a separate ID.
            manager.notify(NOTIFICATION_ID + 1, NotificationCompat.Builder(context, AttendanceSyncWorker.UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download).setContentTitle("Update ready to install")
                .setContentText("Tap to install $tag").setContentIntent(pending).setAutoCancel(true).build())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ApkDownloadWorker", "Update download failed", e)
            if (e is IOException && runAttemptCount < 5) {
                Result.retry()
            } else {
                partial.delete()
                manager.notify(NOTIFICATION_ID + 1, NotificationCompat.Builder(context, AttendanceSyncWorker.UPDATE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Update download failed")
                    .setContentText("Open the app and check for updates to try again.").setAutoCancel(true).build())
                Result.failure()
            }
        }
    }
}
