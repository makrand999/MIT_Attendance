package com.mit.attendance.data.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mit.attendance.BuildConfig
import com.mit.attendance.storage.OutputDirectoryManager
import com.mit.attendance.ui.subjects.NotesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object UpdateChecker {
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/makrand999/MIT_Attendance/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    
    private val downloadClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkForUpdates(context: Context, isManualCheck: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val responseBody = downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("UpdateChecker", "GitHub API request failed: ${response.code}")
                        if (isManualCheck) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Failed to check for updates", Toast.LENGTH_SHORT).show()
                            }
                        }
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

                withContext(Dispatchers.Main) {
                    if (remoteVersion > currentVersion) {
                        val apkUrl = release.assets
                            .firstOrNull { it.name.endsWith(".apk") }
                            ?.browserDownloadUrl

                        Log.d("UpdateChecker", "New version found: ${release.tagName}")
                        showUpdateDialog(context, release, apkUrl)
                    } else if (isManualCheck) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("Update")
                            .setMessage("App is up to date (v${BuildConfig.VERSION_NAME})")
                            .setPositiveButton("Manage Downloads") { _, _ ->
                                val intent = Intent(context, NotesActivity::class.java).apply {
                                    putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_UPDATE)
                                }
                                context.startActivity(intent)
                            }
                            .setNegativeButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Update check failed", e)
                if (isManualCheck) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(context: Context, release: GithubRelease, apkUrl: String?) {
        if (context !is Activity || context.isFinishing || context.isDestroyed) return

        val tagName = release.tagName
        val versionFolder = OutputDirectoryManager.getVersionFolder(context, tagName)
        val destination = File(versionFolder, "MIT_Attendance.apk")
        val isDownloaded = destination.exists()

        MaterialAlertDialogBuilder(context)
            .setTitle("Update Available — ${release.name}")
            .setMessage(release.body)
            .setPositiveButton(if (isDownloaded) "View Updates" else "Download Now") { _, _ ->
                if (isDownloaded) {
                    val intent = Intent(context, NotesActivity::class.java).apply {
                        putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_UPDATE)
                    }
                    context.startActivity(intent)
                } else if (apkUrl != null) {
                    downloadAndInstallDirectly(context, apkUrl, tagName)
                } else {
                    val url = "https://github.com/makrand999/MIT_Attendance/releases/latest"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallDirectly(context: Context, url: String, tagName: String) {
        val activity = context as? AppCompatActivity ?: return
        
        Toast.makeText(context, "Downloading update...", Toast.LENGTH_LONG).show()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val versionFolder = OutputDirectoryManager.getVersionFolder(context, tagName)
                val destination = File(versionFolder, "MIT_Attendance.apk")
                val tempFile = File(versionFolder, "MIT_Attendance.apk.tmp")
                
                if (tempFile.exists()) tempFile.delete()

                val request = Request.Builder().url(url).build()
                
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Failed to download file: ${response.code}")
                    
                    val body = response.body ?: throw Exception("Empty response body")
                    
                    FileOutputStream(tempFile).use { output ->
                        body.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }

                if (destination.exists()) destination.delete()
                tempFile.renameTo(destination)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download complete", Toast.LENGTH_SHORT).show()
                    val intent = Intent(context, NotesActivity::class.java).apply {
                        putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_UPDATE)
                    }
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Download failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
