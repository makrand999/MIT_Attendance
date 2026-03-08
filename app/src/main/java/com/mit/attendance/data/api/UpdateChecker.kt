package com.mit.attendance.data.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mit.attendance.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import androidx.core.net.toUri

object UpdateChecker {
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/makrand999/MIT_Attendance/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdates(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val client = HttpClientHolder.client
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val responseBody = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("UpdateChecker", "GitHub API request failed: ${response.code}")
                        return@withContext
                    }
                    response.body?.string()
                } ?: return@withContext

                val release = try {
                    json.decodeFromString<GithubRelease>(responseBody)
                } catch (e: Exception) {
                    Log.e("UpdateChecker", "Failed to parse JSON", e)
                    return@withContext
                }

                val remoteVersion = release.tagName
                    .removePrefix("v")
                    .toIntOrNull() ?: run {
                    Log.e("UpdateChecker", "Could not parse remote version: ${release.tagName}")
                    return@withContext
                }

                val currentVersion = BuildConfig.VERSION_CODE

                if (remoteVersion > currentVersion) {
                    val apkUrl = release.assets
                        .firstOrNull { it.name.endsWith(".apk") }
                        ?.browserDownloadUrl

                    Log.d("UpdateChecker", "New version found: ${release.tagName}")
                    
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(context, release, apkUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Update check failed", e)
            }
        }
    }

    private fun showUpdateDialog(context: Context, release: GithubRelease, apkUrl: String?) {
        // We need an Activity context to show a MaterialAlertDialog
        if (context !is Activity) {
            Log.e("UpdateChecker", "Cannot show dialog: context is not an Activity")
            return
        }
        
        if (context.isFinishing || context.isDestroyed) return

        MaterialAlertDialogBuilder(context)
            .setTitle("Update Available — ${release.name}")
            .setMessage(release.body)
            .setPositiveButton("Download") { _, _ ->
                val url = apkUrl ?: "https://github.com/makrand999/MIT_Attendance/releases/latest"
                try {
                    // To ensure it opens in an external browser and starts the download correctly,
                    // we use ACTION_VIEW with the explicit category CATEGORY_BROWSABLE.
                    // This prevents internal app handling and forces a browser choice.
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("UpdateChecker", "Could not open download link", e)
                    // Fallback: If no browser is found that can handle the CATEGORY_BROWSABLE,
                    // try a standard VIEW intent.
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (e2: Exception) {
                        Log.e("UpdateChecker", "Complete failure to open link", e2)
                    }
                }
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }
}
