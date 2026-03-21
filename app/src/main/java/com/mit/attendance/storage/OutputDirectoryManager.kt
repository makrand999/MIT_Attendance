package com.mit.attendance.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG       = "OutputDirectoryManager"
private const val APP_ROOT  = "Chaipatti"
private const val TASKS_DIR = "tasks"

// Folder names that are internal scaffolding, never real task output folders
private val INTERNAL_DIR_NAMES = setOf("tasks", "parts", "completed")

object OutputDirectoryManager {

    // ── ROOT / PENDING TASKS ──────────────────────────────────────

    fun getRootFolder(context: Context): File {
        val root = File(context.getExternalFilesDir(null), APP_ROOT)
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun getPendingTasksFolder(context: Context): File {
        val folder = File(getRootFolder(context), TASKS_DIR)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun listPendingTasks(context: Context): List<File> =
        getPendingTasksFolder(context).listFiles()?.filter { it.isFile }?.toList() ?: emptyList()

    // ── LIST TASK FOLDERS ─────────────────────────────────────────
    fun listTaskFolders(context: Context): List<TaskFolder> {
        val root = getRootFolder(context)
        if (!root.exists()) return emptyList()

        return root.listFiles()
            ?.filter { it.isDirectory && it.name !in INTERNAL_DIR_NAMES }
            ?.map { folder ->
                val hasCheckpoint = File(folder, ".checkpoint.json").exists()

                val outputFiles = folder.listFiles()
                    ?.filter { f ->
                        f.isFile &&
                                !f.name.startsWith(".") &&
                                !f.name.endsWith(".tmp")
                    }
                    ?.map { it.absolutePath }
                    ?: emptyList()

                val isCompleted = !hasCheckpoint && outputFiles.isNotEmpty()

                TaskFolder(
                    name        = folder.name,
                    path        = folder.absolutePath,
                    isCompleted = isCompleted,
                    outputFiles = outputFiles
                )
            }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    // ── AGENT FILESYSTEM COMMANDS ─────────────────────────────────

    fun listRecursive(context: Context, dir: File? = null, indent: String = ""): String {
        val targetDir = dir ?: getRootFolder(context)
        if (!targetDir.exists()) return "Root folder does not exist."
        val sb = StringBuilder()
        targetDir.listFiles()
            ?.filter { f ->
                !f.name.startsWith(".") &&
                        f.name != "parts"
            }
            ?.forEach { file ->
                sb.appendLine("$indent${if (file.isDirectory) "[DIR] " else "- "}${file.name}")
                if (file.isDirectory) {
                    sb.append(listRecursive(context, file, "$indent  "))
                }
            }
        return sb.toString()
    }

    fun readFile(context: Context, path: String): String {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(getRootFolder(context), path)
            if (file.exists() && file.isFile) file.readText()
            else "Error: File '$path' not found or is a directory."
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    /**
     * Returns the number of lines in a file.
     */
    fun countLines(context: Context, path: String): String {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(getRootFolder(context), path)
            if (file.exists() && file.isFile) {
                val lineCount = file.useLines { it.count() }
                "File '$path' has $lineCount lines."
            } else {
                "Error: File '$path' not found or is a directory."
            }
        } catch (e: Exception) {
            "Error counting lines in file: ${e.message}"
        }
    }

    // ── TASK FOLDER CREATION ──────────────────────────────────────
    fun createTaskFolder(context: Context, taskDescription: String): String? {
        val timestamp  = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val folderName = "${sanitize(taskDescription)}_$timestamp"
        val folder     = File(getRootFolder(context), folderName)
        return try {
            folder.mkdirs()
            folder.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "createTaskFolder failed: ${e.message}")
            null
        }
    }

    private fun sanitize(input: String): String = input
        .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        .take(40).trim('_')

    // ── MODEL ─────────────────────────────────────────────────────

    data class TaskFolder(
        val name: String,
        val path: String,
        val isCompleted: Boolean,
        val outputFiles: List<String> = emptyList()
    )
}
