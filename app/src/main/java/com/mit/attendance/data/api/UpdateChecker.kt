package com.mit.attendance.data.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mit.attendance.BuildConfig
import com.mit.attendance.service.ApkDownloadWorker
import com.mit.attendance.storage.OutputDirectoryManager
import com.mit.attendance.ui.subjects.UpdateInstallActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val RELEASE_URL =
        "https://api.github.com/repos/makrand999/MIT_Attendance/releases/latest"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(context: Context, isManualCheck: Boolean = false) {
        try {
            val release = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(RELEASE_URL)
                    .header("Accept", "application/vnd.github+json").build()).execute().use {
                    check(it.isSuccessful) { "GitHub returned ${it.code}" }
                    json.decodeFromString<GithubRelease>(checkNotNull(it.body).string())
                }
            }
            // Existing releases use v<versionCode>, not semantic version tags.
            val remoteVersion = release.tagName.removePrefix("v").toIntOrNull()
                ?: error("Release tag must be v followed by the Android version code")
            withContext(Dispatchers.Main) {
                if (remoteVersion <= BuildConfig.VERSION_CODE) {
                    if (isManualCheck) Toast.makeText(context,
                        "App is up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val activity = context as? Activity ?: return@withContext
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
                val asset = apks.singleOrNull()
                    ?: error("Release must contain exactly one compatible, universal APK")
                val destination = File(OutputDirectoryManager.getVersionFolder(context,
                    "v$remoteVersion"), "MIT_Attendance.apk")
                val downloaded = withContext(Dispatchers.IO) {
                    if (!destination.isFile) false else try {
                        UpdateInstallActivity.validateApk(context, destination, remoteVersion.toLong())
                        true
                    } catch (e: Exception) {
                        // Older download paths could leave incomplete APKs under the final name.
                        check(destination.delete()) { "Could not remove invalid cached update" }
                        false
                    }
                }
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                MaterialAlertDialogBuilder(context)
                    .setTitle("Update Available — ${release.name.ifBlank { release.tagName }}")
                    .setMessage(release.body)
                    .setPositiveButton(if (downloaded) "Install" else "Download") { _, _ ->
                        if (downloaded) {
                            context.startActivity(Intent(context, UpdateInstallActivity::class.java)
                                .putExtra(UpdateInstallActivity.EXTRA_APK_PATH, destination.absolutePath))
                        } else {
                            ApkDownloadWorker.enqueue(context, asset.browserDownloadUrl,
                                "v$remoteVersion", asset.size, asset.digest)
                            Toast.makeText(context, "Update download queued", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Update check failed", e)
            if (isManualCheck) withContext(Dispatchers.Main) {
                Toast.makeText(context, "Could not check for updates: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
