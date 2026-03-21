package com.mit.attendance.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "CheckpointManager"

// ─────────────────────────────────────────
//  CHECKPOINT MANAGER  v2
//
//  Changes vs v1:
//
//  [A] saveAtomic() — writes to a .tmp file first, then renames.
//      rename() is atomic on most Android filesystems (ext4, f2fs).
//      A crash mid-write can no longer corrupt the checkpoint file.
//      The old save() is kept for callers that don't need atomicity.
//
//  [B] load() falls back to the .tmp file if the main file is missing
//      or corrupt. This recovers from a crash that completed the write
//      to .tmp but failed before the rename.
//
//  File layout:
//    {outputDirectory}/.checkpoint.json      ← canonical
//    {outputDirectory}/.checkpoint.json.tmp  ← written first, then renamed
// ─────────────────────────────────────────
class CheckpointManager(outputDirectory: String) {

    private val file    = File("$outputDirectory/.checkpoint.json")
    private val tmpFile = File("$outputDirectory/.checkpoint.json.tmp")

    // ── SAVE (non-atomic, kept for compatibility) ──────────────────
    fun save(state: CheckpointState) {
        try {
            val json = buildJson(state)
            file.parentFile?.mkdirs()
            file.writeText(json, Charsets.UTF_8)
            Log.d(TAG, "💾 Checkpoint saved — ${state.completedTasks.size} tasks done")
        } catch (e: Exception) {
            Log.e(TAG, "Checkpoint save failed: ${e.message}")
        }
    }

    // ── SAVE ATOMIC ───────────────────────────────────────────────
    // [A] Write → tmp, rename → canonical. Safe against mid-crash corruption.
    fun saveAtomic(state: CheckpointState) {
        try {
            val json = buildJson(state)
            file.parentFile?.mkdirs()
            tmpFile.writeText(json, Charsets.UTF_8)
            // renameTo is atomic on ext4/f2fs (Android default filesystems)
            if (!tmpFile.renameTo(file)) {
                // Rename failed (cross-device?), fall back to direct write
                file.writeText(json, Charsets.UTF_8)
                tmpFile.delete()
            }
            Log.d(TAG, "💾 Checkpoint saved (atomic) — ${state.completedTasks.size} tasks done")
        } catch (e: Exception) {
            Log.e(TAG, "Checkpoint atomic save failed: ${e.message}")
            // Last resort: try direct write
            try { file.writeText(buildJson(state), Charsets.UTF_8) } catch (_: Exception) { }
        }
    }

    // ── LOAD ──────────────────────────────────────────────────────
    // [B] Falls back to .tmp if main file is missing or corrupt.
    fun load(): CheckpointState? {
        val source = when {
            file.exists()    -> file
            tmpFile.exists() -> tmpFile.also {
                Log.w(TAG, "📂 Loading from .tmp fallback (main file missing)")
            }
            else -> return null
        }

        return try {
            val o = JSONObject(source.readText(Charsets.UTF_8))
            val completed = (0 until o.getJSONArray("completedTasks").length())
                .map { o.getJSONArray("completedTasks").getInt(it) }
                .toMutableSet()
            val topics = (0 until o.getJSONArray("usedTopics").length())
                .map { o.getJSONArray("usedTopics").getString(it) }
                .toMutableList()
            CheckpointState(
                configJson     = o.getString("configJson"),
                completedTasks = completed,
                usedTopics     = topics,
                lastUpdated    = o.optLong("lastUpdated", 0L)
            ).also {
                Log.d(TAG, "📂 Checkpoint loaded — ${it.completedTasks.size} already done")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Checkpoint load failed from ${source.name}: ${e.message}")
            // If main file is corrupt, try tmp as a last resort
            if (source == file && tmpFile.exists()) {
                Log.w(TAG, "Retrying load from .tmp")
                return load().also { tmpFile.delete() }
            }
            null
        }
    }

    // ── EXISTS ────────────────────────────────────────────────────
    fun exists(): Boolean = file.exists() || tmpFile.exists()

    // ── CLEAR ─────────────────────────────────────────────────────
    fun clear() {
        if (file.exists())    file.delete()
        if (tmpFile.exists()) tmpFile.delete()
        Log.d(TAG, "🗑️ Checkpoint cleared")
    }

    // ── INTERNAL ──────────────────────────────────────────────────
    private fun buildJson(state: CheckpointState): String =
        JSONObject().apply {
            put("configJson",     state.configJson)
            put("completedTasks", JSONArray(state.completedTasks))
            put("usedTopics",     JSONArray(state.usedTopics))
            put("lastUpdated",    System.currentTimeMillis())
        }.toString(2)

    // ── MODEL ─────────────────────────────────────────────────────
    data class CheckpointState(
        val configJson: String,
        val completedTasks: MutableSet<Int> = mutableSetOf(),
        val usedTopics: MutableList<String> = mutableListOf(),
        val lastUpdated: Long = 0L
    ) {
        fun remainingTasks(total: Int): List<Int> =
            (1..total).filter { it !in completedTasks }

        val completedCount get() = completedTasks.size

        fun summary(total: Int) =
            "Resuming: ${completedTasks.size}/$total done, ${remainingTasks(total).size} remaining"
    }
}