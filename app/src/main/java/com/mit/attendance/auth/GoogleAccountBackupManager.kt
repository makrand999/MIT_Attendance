package com.mit.attendance.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

object GoogleAccountBackupManager {
    private const val TAG = "GoogleAccountBackup"
    private const val BACKUP_REPO_NAME = "mit-attendance-backup"
    private const val BACKUP_FOLDER_NAME = "google"
    private const val BACKUP_FILE_NAME = "all_accounts_session.dat"
    private const val LOCAL_STORAGE_FILE_NAME = "storage_state.json"

    suspend fun backupAllGoogleAccounts(
        context: Context,
        prefs: UserPreferences,
        githubToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val storageFile = getLocalStorageFile(context)
            require(storageFile.exists()) { "Google session not found locally. Log in first." }

            val plaintext = storageFile.readText()
            val capturedAt = storageFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

            pullBackupRepo(context, prefs, githubToken)

            val backupFile = getSharedBackupFile(context)
            backupFile.parentFile?.mkdirs()
            backupFile.writeText(encodePayload(plaintext, capturedAt))
            pushBackupRepo(context, prefs, githubToken)
            backupFile.absolutePath
        }
    }

    suspend fun restoreAllGoogleAccountsIfAvailable(
        context: Context,
        prefs: UserPreferences,
        githubToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            pullBackupRepo(context, prefs, githubToken)

            val backupFile = getSharedBackupFile(context)
            if (!backupFile.exists()) {
                return@runCatching false
            }

            val restored = decodePayload(backupFile.readText())
            val storageFile = getLocalStorageFile(context)
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(restored.plaintext)
            storageFile.setLastModified(restored.capturedAt)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Restore failed", error)
            false
        }
    }

    private fun ensurePythonStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private suspend fun pullBackupRepo(context: Context, prefs: UserPreferences, githubToken: String) {
        ensurePythonStarted(context)
        val module = Python.getInstance().getModule("bridge")
        val result = module.callAttr(
            "pull_repo",
            accountBackupRoot(context).absolutePath,
            BACKUP_REPO_NAME,
            githubToken,
            prefs.getFirstNameSnapshot() ?: "User"
        ).toString()
        check(result.startsWith("Pull Successful")) { result }
        Log.d(TAG, "Pull result: $result")
    }

    private suspend fun pushBackupRepo(context: Context, prefs: UserPreferences, githubToken: String) {
        ensurePythonStarted(context)
        val module = Python.getInstance().getModule("bridge")
        val result = module.callAttr(
            "push_repo",
            accountBackupRoot(context).absolutePath,
            BACKUP_REPO_NAME,
            githubToken,
            prefs.getFirstNameSnapshot() ?: "User"
        ).toString()

        if (!result.contains("Push Successful", ignoreCase = true)) {
            error(result)
        }
        Log.d(TAG, "Push result: $result")
    }

    private fun getSharedBackupFile(context: Context): File {
        val repoDir = File(accountBackupRoot(context), BACKUP_REPO_NAME)
        return File(File(repoDir, BACKUP_FOLDER_NAME), BACKUP_FILE_NAME)
    }

    private fun accountBackupRoot(context: Context): File {
        val username = GitHubAccountStore(context).requireAccount().username.lowercase(java.util.Locale.ROOT)
        return File(OutputDirectoryManager.getBackupsFolder(context), username).apply { mkdirs() }
    }

    private fun getLocalStorageFile(context: Context): File {
        return File(context.getExternalFilesDir(null), LOCAL_STORAGE_FILE_NAME)
    }

    private fun encodePayload(
        plaintext: String,
        capturedAt: Long
    ): String {
        val encoded = Base64.encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        return JSONObject().apply {
            put("v", 1)
            put("ts", capturedAt)
            put("encoding", "base64")
            put("data", encoded)
        }.toString()
    }

    private fun decodePayload(payload: String): RestoredPayload {
        val json = JSONObject(payload)
        val plaintext = Base64.decode(json.getString("data"), Base64.NO_WRAP).toString(StandardCharsets.UTF_8)

        return RestoredPayload(
            plaintext = plaintext,
            capturedAt = json.optLong("ts", System.currentTimeMillis())
        )
    }

    private data class RestoredPayload(
        val plaintext: String,
        val capturedAt: Long
    )
}
