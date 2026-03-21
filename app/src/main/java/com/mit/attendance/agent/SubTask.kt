package com.mit.attendance.agent

import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────
//  SUBTASK  — v2 (FREE OUTPUT STRUCTURE)
//
//  Changes vs v1:
//
//  [A] SubTask.path is now the PRIMARY filename for that subtask's output.
//      It is always respected — not just when outputPerSubtask=true.
//      The LLM sets this in the plan JSON. If blank, a safe fallback is used.
//
//  [B] PlanManifest — new class parsed from the plan reply alongside subtasks.
//      The LLM declares its output intent at plan time:
//
//        "output_mode": "single_file"    → one combined file (old default)
//        "output_mode": "multi_file"     → one file per subtask, LLM names them
//        "output_mode": "free"           → LLM decides per-subtask, may mix
//
//      "output_mode" is optional — defaults to "single_file" so existing
//      configs that don't mention it continue to work unchanged.
//
//      "suggested_structure" is a free-text description of what the LLM
//      intends to produce (e.g. "main.c + utils.h + Makefile"). Shown in
//      the UI progress card so the user knows what's coming.
//
//  [C] parseFromPlanReply() now also extracts the PlanManifest from an
//      optional top-level wrapper object:
//
//        {
//          "output_mode": "multi_file",
//          "suggested_structure": "main.c, utils.h, Makefile",
//          "subtasks": [ ... ]
//        }
//
//      OR the old bare array format:
//
//        [ {...}, {...} ]
//
//      Both are accepted — backward compatible.
// ─────────────────────────────────────────

// ── [B] PLAN MANIFEST ────────────────────────────────────────────
data class PlanManifest(
    val outputMode: OutputMode,
    val suggestedStructure: String   // human-readable, for UI display
) {
    enum class OutputMode {
        SINGLE_FILE,   // one combined output (default, backward-compatible)
        MULTI_FILE,    // one file per subtask, each with its own path
        FREE           // LLM decides per subtask — may produce any structure
    }

    companion object {
        val DEFAULT = PlanManifest(OutputMode.SINGLE_FILE, "")

        fun fromString(raw: String): OutputMode = when (raw.lowercase().trim()) {
            "multi_file", "multi"  -> OutputMode.MULTI_FILE
            "free", "freestyle"    -> OutputMode.FREE
            else                   -> OutputMode.SINGLE_FILE
        }
    }
}

// ── SUBTASK ───────────────────────────────────────────────────────
data class SubTask(
    val index: Int,
    val title: String,
    val detail: String,
    val dedupeValue: String,
    val rawJson: String,
    // [A] path is now always meaningful — the LLM's chosen filename for this subtask
    val path: String? = null
) {
    companion object {

        // ── [C] PARSE PLAN REPLY ──────────────────────────────────
        /**
         * Parses the plan reply into (subtasks, manifest).
         *
         * Accepts two formats:
         *
         * Format 1 — wrapped object (new, preferred):
         *   {
         *     "output_mode": "multi_file",
         *     "suggested_structure": "main.c, utils.h",
         *     "subtasks": [ {"title":"...","detail":"...","path":"main.c"}, ... ]
         *   }
         *
         * Format 2 — bare array (original, backward-compatible):
         *   [ {"title":"...","detail":"..."}, ... ]
         *
         * Returns Pair(subtasks, manifest). manifest is PlanManifest.DEFAULT
         * for bare-array replies.
         */
        fun parseFromPlanReply(
            reply: String,
            dedupeField: String?
        ): Pair<List<SubTask>, PlanManifest> {

            val clean = reply
                .replace(Regex("(?i)```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            // Try wrapped object format first
            val objStart = clean.indexOf('{')
            val arrStart = clean.indexOf('[')

            val manifest: PlanManifest
            val arr: JSONArray

            if (objStart != -1 && (arrStart == -1 || objStart < arrStart)) {
                // Looks like a wrapped object — try to parse it
                val objEnd = clean.lastIndexOf('}')
                if (objEnd > objStart) {
                    val obj = try {
                        JSONObject(clean.substring(objStart, objEnd + 1))
                    } catch (_: Exception) {
                        null
                    }

                    if (obj != null && obj.has("subtasks")) {
                        manifest = PlanManifest(
                            outputMode         = PlanManifest.fromString(obj.optString("output_mode", "single_file")),
                            suggestedStructure = obj.optString("suggested_structure", "")
                        )
                        arr = obj.getJSONArray("subtasks")
                    } else {
                        // Object exists but no "subtasks" key — fall through to array scan
                        manifest = PlanManifest.DEFAULT
                        arr = extractArray(clean)
                    }
                } else {
                    manifest = PlanManifest.DEFAULT
                    arr = extractArray(clean)
                }
            } else {
                // Bare array format
                manifest = PlanManifest.DEFAULT
                arr = extractArray(clean)
            }

            val subtasks = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SubTask(
                    index       = i + 1,
                    title       = o.optString("title", "Subtask ${i + 1}"),
                    detail      = o.optString("detail", ""),
                    dedupeValue = if (dedupeField != null) o.optString(dedupeField, "") else "",
                    rawJson     = o.toString(),
                    path        = o.optString("path", null)?.takeIf { it.isNotBlank() }
                )
            }

            return Pair(subtasks, manifest)
        }

        // Legacy overload — returns just subtasks, discards manifest
        // Kept so any callers outside WorkerAgent don't break
        fun parseFromPlanReplyLegacy(reply: String, dedupeField: String?): List<SubTask> =
            parseFromPlanReply(reply, dedupeField).first

        private fun extractArray(clean: String): JSONArray {
            val start = clean.indexOf('[')
            val end   = clean.lastIndexOf(']')
            if (start == -1 || end == -1) error("No JSON array found in plan reply")
            return JSONArray(clean.substring(start, end + 1))
        }
    }
}

// ── SOLVED SUBTASK ────────────────────────────────────────────────
data class SolvedSubTask(
    val subtask: SubTask,
    val content: String,
    val filePath: String,
    val success: Boolean,
    val errorMessage: String = "",
    // LLM-generated plain-English summary of what this part contains.
    // Generated immediately after solving in the same warm session.
    // Used as prior-parts context for subsequent subtasks instead of
    // raw code truncation — the LLM's own words about its own output.
    val abstract: String = ""
)

// ── TOP-LEVEL TASK ────────────────────────────────────────────────
data class TopLevelTask(
    val index: Int,
    val filename: String,    // fallback name — used only when LLM doesn't provide one
    val filePath: String     // fallback path
) {
    enum class Stage { PLAN, SOLVE, COMBINE, DONE, FAILED }

    var stage: Stage = Stage.PLAN
    var subtasks: List<SubTask> = emptyList()
    var solved: MutableList<SolvedSubTask> = mutableListOf()
    var finalContent: String = ""
    var chosenDedupeValue: String = ""
    // [B] Set after plan is parsed — drives routing in WorkerAgent
    var planManifest: PlanManifest = PlanManifest.DEFAULT
    // [B] All files actually written to disk for this task
    var outputFilePaths: MutableList<String> = mutableListOf()
}