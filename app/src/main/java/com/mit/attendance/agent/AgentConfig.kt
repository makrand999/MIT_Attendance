package com.mit.attendance.agent

import org.json.JSONObject

// ─────────────────────────────────────────
//  AGENT CONFIG  — v10 (LARGE SCALE AWARE)
//
//  Changes vs v9:
//
//  [A] META_AGENT_SYSTEM_PROMPT now includes instructions for
//      LARGE SCALE REQUESTS (e.g., 3000 lines). It explains that
//      LLMs have a per-message output limit (~40-60 lines for code),
//      so large files MUST be split into many subtasks (e.g. 50+).
//
//  [B] Added "count <path>" to the filesystem tools.
//      MetaAgent and WorkerAgent can use this to see how much they've
//      actually written so they can adjust their remaining subtasks.
//
//  [C] Instructs the MetaAgent to do MATH:
//      "Target 3000 lines / 60 lines per subtask = 50 subtasks."
// ─────────────────────────────────────────
data class AgentConfig(
    val taskDescription: String,
    val fileExtension: String,
    val fileNameTemplate: String,
    val outputDirectory: String,
    val totalTasks: Int,
    val planPrompt: String,
    val solvePrompt: String,
    val combinePrompt: String = "",
    val hasCombineStep: Boolean = true,
    val outputPerSubtask: Boolean = false,
    val dedupeField: String? = "title",
    val maxRetries: Int = 3,
    val sessionResetInterval: Int = 3,
    val outputAnalysisPrompt: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("taskDescription",      taskDescription)
        put("fileExtension",        fileExtension)
        put("fileNameTemplate",     fileNameTemplate)
        put("outputDirectory",      outputDirectory)
        put("totalTasks",           totalTasks)
        put("planPrompt",           planPrompt)
        put("solvePrompt",          solvePrompt)
        put("combinePrompt",        combinePrompt)
        put("hasCombineStep",       hasCombineStep)
        put("outputPerSubtask",     outputPerSubtask)
        put("dedupeField",          dedupeField ?: "")
        put("maxRetries",           maxRetries)
        put("sessionResetInterval", sessionResetInterval)
        put("outputAnalysisPrompt", outputAnalysisPrompt)
    }.toString(2)

    companion object {
        fun fromJson(json: String): AgentConfig {
            val o = JSONObject(json)
            return AgentConfig(
                taskDescription      = o.getString("taskDescription"),
                fileExtension        = o.getString("fileExtension"),
                fileNameTemplate     = o.getString("fileNameTemplate"),
                outputDirectory      = o.getString("outputDirectory"),
                totalTasks           = o.getInt("totalTasks"),
                planPrompt           = o.getString("planPrompt"),
                solvePrompt          = o.getString("solvePrompt"),
                combinePrompt        = o.optString("combinePrompt", ""),
                hasCombineStep       = o.optBoolean("hasCombineStep", true),
                outputPerSubtask     = o.optBoolean("outputPerSubtask", false),
                dedupeField          = o.optString("dedupeField", "title").ifBlank { null },
                maxRetries           = o.optInt("maxRetries", 3),
                sessionResetInterval = o.optInt("sessionResetInterval", 3),
                outputAnalysisPrompt = o.optString("outputAnalysisPrompt", "")
            )
        }

        val META_AGENT_SYSTEM_PROMPT = """
You are a powerful Agent Architect. You design automated workflows for a Worker Agent.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STEP 0 — UNDERSTAND THE TASK FIRST
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Before writing any prompt, REASON about scale:

1. TARGET LINE COUNT — Did the user ask for a specific length (e.g. 3000 lines)?
   ChatGPT usually outputs ONLY 40–60 lines of code per message.
   To hit 3000 lines, you MUST split the task into ~50–60 subtasks.
   DO NOT try to hit 3000 lines with 5 subtasks; the worker will fail or truncate.

2. SCALE MATH:
   Subtasks needed = (Target Lines) / 50.
   Example: "3000 lines of code" -> split into 60 subtasks, each doing ~50 lines.

3. FILE STRUCTURE — one file or several?
   "a script that does X"         -> single_file
   "a project with modules"       -> multi_file

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
OUTPUT MODES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  single_file  — merged into ONE file.
  multi_file   — each subtask produces its OWN file.
  free         — Agent decides per subtask.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
COMPLEXITY SIZING TABLE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Output size / complexity          → Subtasks   → Mode
  ──────────────────────────────────────────────────────
  ≤ 150 lines, single concept       →  1-3       → single_file
  400–800 lines, clear modules      →  8-15      → single_file or multi_file
  1000+ lines                       →  20+       → single_file (with many parts)
  3000+ lines                       →  60+       → single_file

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FILESYSTEM TOOLS (Available to you and Worker)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  "ls"           — list root folder
  "ls -R"        — list all files recursively
  "cat <path>"   — read any file
  "count <path>" — returns the line count of a file. Use this to verify progress!

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PLAN JSON FORMAT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
The Worker's plan reply must be a JSON OBJECT:
  {
    "output_mode": "single_file",
    "suggested_structure": "main.c",
    "subtasks": [
      { "title": "Part 1: Header",  "detail": "...", "path": "main.c"  },
      ...
    ]
  }

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
OUTPUT FORMAT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```agent_config
{
  "taskDescription": "One clear sentence",
  "fileExtension": "c",
  "fileNameTemplate": "output.c",
  "outputDirectory": "AGENT_OUTPUT_DIR",
  "totalTasks": 1,
  "planPrompt": "...",
  "solvePrompt": "...",
  "combinePrompt": "..."
}
```
        """.trimIndent()
    }
}
