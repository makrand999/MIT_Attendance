package com.mit.attendance.agent


import android.util.Log
import java.io.File

private const val TAG = "FileHandler"

// ─────────────────────────────────────────
//  FILE HANDLER
//  Handles CREATE / EDIT / DELETE on Android internal storage.
//  All paths are absolute (use context.filesDir or getExternalFilesDir as base).
// ─────────────────────────────────────────
object FileHandler {

    // ── CREATE ────────────────────────────────────────────────────
    /**
     * Writes [content] to [path], creating parent directories if needed.
     * If the file already exists it is overwritten.
     */
    fun create(path: String, content: String): FileResult {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            Log.d(TAG, "✅ Created: $path (${content.length} chars)")
            FileResult(success = true, path = path, message = "Created")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Create failed: $path — ${e.message}")
            FileResult(success = false, path = path, message = "Create failed: ${e.message}")
        }
    }

    // ── EDIT ─────────────────────────────────────────────────────
    /**
     * Replaces the full content of an existing file.
     * If file does not exist, creates it (same as CREATE).
     */
    fun edit(path: String, newContent: String): FileResult {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(newContent, Charsets.UTF_8)
            Log.d(TAG, "✅ Edited: $path")
            FileResult(success = true, path = path, message = "Edited")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Edit failed: $path — ${e.message}")
            FileResult(success = false, path = path, message = "Edit failed: ${e.message}")
        }
    }

    // ── DELETE ────────────────────────────────────────────────────
    /**
     * Deletes a file at [path].
     * No-ops silently if the file doesn't exist.
     */
    fun delete(path: String): FileResult {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "🗑️ Deleted: $path")
                FileResult(success = true, path = path, message = "Deleted")
            } else {
                FileResult(success = true, path = path, message = "Already absent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Delete failed: $path — ${e.message}")
            FileResult(success = false, path = path, message = "Delete failed: ${e.message}")
        }
    }

    // ── READ (for EDIT tasks that need current content) ───────────
    fun read(path: String): String? {
        return try {
            File(path).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ── LIST files matching a glob pattern ───────────────────────
    /**
     * Lists files in [directory] whose names match [glob].
     * Supports simple wildcards: * matches anything.
     * E.g. glob = "*.cs" returns all .cs files.
     */
    fun listMatching(directory: String, glob: String): List<String> {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val regex = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .let { Regex(it) }
        return dir.listFiles()
            ?.filter { it.isFile && regex.matches(it.name) }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    // ── RESULT ────────────────────────────────────────────────────
    data class FileResult(
        val success: Boolean,
        val path: String,
        val message: String
    )
}
