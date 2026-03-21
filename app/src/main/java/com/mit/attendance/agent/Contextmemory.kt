package com.mit.attendance.agent

// ─────────────────────────────────────────
//  CONTEXT MEMORY  — v2 (INTELLIGENT SIZING)
//
//  Changes vs v1:
//
//  [A] buildPlanPrompt() now calls estimateComplexity() to inject a
//      SIZING HINT block before the user's corePrompt.
//      This tells the planning LLM exactly how many subtasks to create
//      based on cues in the task description (line counts, keyword
//      signals, file counts). Previously the LLM had zero guidance
//      and would default to an arbitrary split count (often 4).
//
//  [B] estimateComplexity() — new private function.
//      Scans taskDescription for:
//        • Explicit line counts  ("100 lines", "~200 lines")
//        • Explicit file counts  ("5 files", "two files")
//        • Complexity keywords   ("simple", "basic" vs "full", "complete", "system")
//      Returns a ComplexityHint with a recommended subtask range and
//      a human-readable rationale that gets injected into the prompt.
//
//  Everything else is unchanged from v1.
// ─────────────────────────────────────────
object ContextMemory {

    private const val TASK_DESC_LIMIT  = 500
    private const val PLAN_CARD_LIMIT  = 800
    private const val PART_SNIPPET_LEN = 300
    private const val MAX_PRIOR_PARTS  = 3
    private const val DETAIL_LIMIT     = 500

    // ── [B] COMPLEXITY HINT ───────────────────────────────────────
    private data class ComplexityHint(
        val minSubtasks: Int,
        val maxSubtasks: Int,
        val rationale: String
    )

    /**
     * [B] Estimates how many subtasks the planning LLM should produce,
     * based on explicit signals in the task description.
     *
     * Priority order (highest wins):
     *   1. Explicit line count mention   → maps via line-count table
     *   2. Explicit file count mention   → one subtask per file
     *   3. Complexity keyword signals    → small/simple vs full/system
     *   4. Default (no signals found)    → 2–3 (safe middle ground)
     */
    private fun estimateComplexity(taskDescription: String): ComplexityHint {
        val desc = taskDescription.lowercase()

        // ── 1. Explicit line count ────────────────────────────────
        // Matches: "100 lines", "~200 lines", "about 150 lines", "100-line"
        val lineCountRegex = Regex("""(?:~|about\s+)?(\d{2,4})\s*[-\s]?lines?""")
        val lineMatch = lineCountRegex.find(desc)
        if (lineMatch != null) {
            val lines = lineMatch.groupValues[1].toIntOrNull() ?: 0
            return when {
                lines <= 150 -> ComplexityHint(1, 1,
                    "Task mentions ~$lines lines. That fits in ONE part — no splitting needed.")
                lines <= 400 -> ComplexityHint(1, 2,
                    "Task mentions ~$lines lines. Use 1–2 parts maximum.")
                lines <= 800 -> ComplexityHint(2, 3,
                    "Task mentions ~$lines lines. Use 2–3 coherent parts.")
                lines <= 1500 -> ComplexityHint(3, 5,
                    "Task mentions ~$lines lines. Use 3–5 parts (one per logical module).")
                else -> ComplexityHint(5, 8,
                    "Task mentions ~$lines lines. Use 5–8 parts (one per file/module).")
            }
        }

        // ── 2. Explicit file count ────────────────────────────────
        // Matches: "3 files", "two files", "5 separate files"
        val fileCountRegex = Regex("""(\d+|one|two|three|four|five|six|seven|eight)\s+(?:separate\s+)?files?""")
        val fileMatch = fileCountRegex.find(desc)
        if (fileMatch != null) {
            val raw = fileMatch.groupValues[1]
            val count = raw.toIntOrNull() ?: when (raw) {
                "one" -> 1; "two" -> 2; "three" -> 3; "four" -> 4
                "five" -> 5; "six" -> 6; "seven" -> 7; "eight" -> 8
                else -> 3
            }
            return ComplexityHint(count, count,
                "Task mentions $count files → use exactly $count subtasks (one per file).")
        }

        // ── 3. Complexity keyword signals ────────────────────────
        val smallSignals = listOf("simple", "basic", "small", "quick", "short", "tiny",
            "single", "minimal", "demo", "example", "snippet", "one function",
            "one class", "100 line", "50 line")
        val largeSignals = listOf("full", "complete", "entire", "production", "system",
            "platform", "framework", "comprehensive", "end-to-end", "full-stack",
            "architecture", "microservice", "monorepo")

        val smallScore = smallSignals.count { desc.contains(it) }
        val largeScore = largeSignals.count { desc.contains(it) }

        return when {
            smallScore > largeScore -> ComplexityHint(1, 2,
                "Task uses small-scope language ('${smallSignals.first { desc.contains(it) }}'). Use 1–2 subtasks.")
            largeScore > smallScore -> ComplexityHint(4, 6,
                "Task uses large-scope language ('${largeSignals.first { desc.contains(it) }}'). Use 4–6 subtasks.")
            else -> ComplexityHint(2, 3,
                "No explicit size cues found. Use 2–3 subtasks as a safe default.")
        }
    }

    // ── PUBLIC: build PLAN prompt ─────────────────────────────────
    /**
     * Fully self-contained prompt for the PLAN step.
     * [A] Injects a SIZING HINT block derived from estimateComplexity().
     * The model has never heard of this task before.
     */
    fun buildPlanPrompt(
        taskDescription: String,
        taskIndex: Int,
        totalTasks: Int,
        dedupeInstruction: String,
        corePrompt: String
    ): String = buildString {

        // [A] Estimate complexity before building the prompt
        val hint = estimateComplexity(taskDescription)

        appendLine("════════════════════════════════════════")
        appendLine("MISSION BRIEF — read this fully before responding")
        appendLine("════════════════════════════════════════")
        appendLine()
        appendLine("YOU ARE: An AI planning agent.")
        appendLine("YOUR ONLY JOB RIGHT NOW: Produce a structured plan (JSON array) for the task below.")
        appendLine()
        appendLine("TASK (${taskIndex} of ${totalTasks}):")
        appendLine(taskDescription.take(TASK_DESC_LIMIT))
        appendLine()

        if (dedupeInstruction.isNotBlank() && dedupeInstruction != "none") {
            appendLine("ALREADY COMPLETED — do NOT repeat these:")
            appendLine(dedupeInstruction)
            appendLine()
        }

        // [A] SIZING HINT — the critical new block
        appendLine("════════════════════════════════════════")
        appendLine("SIZING GUIDANCE  ← READ THIS BEFORE SPLITTING")
        appendLine("════════════════════════════════════════")
        appendLine("Analysis of this task:")
        appendLine("  ${hint.rationale}")
        appendLine()
        appendLine("Recommended subtask count: ${hint.minSubtasks}–${hint.maxSubtasks}")
        appendLine()
        appendLine("RULES:")
        appendLine("  • Use the MINIMUM number of subtasks that keeps each part coherent.")
        appendLine("  • Each subtask must map to a logical unit (a module, a file, a concept).")
        appendLine("  • NEVER split just to have more subtasks.")
        appendLine("  • If the entire task can be done in ONE coherent part, use 1 subtask.")
        appendLine()

        appendLine("════════════════════════════════════════")
        appendLine("INSTRUCTIONS")
        appendLine("════════════════════════════════════════")
        appendLine(corePrompt)
        appendLine()
        appendLine("OUTPUT RULES:")
        appendLine("• Return ONLY a raw JSON array.")
        appendLine("• No markdown fences, no prose, no explanation.")
        appendLine("• Each element must have at minimum: \"title\" and \"detail\".")
        appendLine("• Example: [{\"title\":\"Step 1\",\"detail\":\"What to do\"}]")
    }

    // ── PUBLIC: build SOLVE prompt ────────────────────────────────
    fun buildSolvePrompt(
        taskDescription: String,
        taskIndex: Int,
        totalTasks: Int,
        outputFilename: String,
        fileExtension: String,
        planCard: String,
        solvedParts: List<SolvedSubTask>,
        subtask: SubTask,
        corePrompt: String,
        attemptNumber: Int,
        lastError: String = ""
    ): String = buildString {

        appendLine("════════════════════════════════════════")
        appendLine("MISSION BRIEF — read this fully before responding")
        appendLine("════════════════════════════════════════")
        appendLine()
        appendLine("YOU ARE: An AI coding/writing agent completing one part of a larger task.")
        appendLine()
        appendLine("OVERALL GOAL (task ${taskIndex} of ${totalTasks}):")
        appendLine(taskDescription.take(TASK_DESC_LIMIT))
        appendLine()
        appendLine("OUTPUT FILE: $outputFilename")
        appendLine()

        if (planCard.isNotBlank()) {
            appendLine("════ FULL PLAN (all ${subtask.index.let { _ -> planCard.lines().count { it.trimStart().startsWith("[") } }} parts) ════")
            appendLine(planCard.take(PLAN_CARD_LIMIT))
            appendLine()
        }

        val recentSolved = solvedParts
            .filter { it.success && it.content.isNotBlank() }
            .takeLast(MAX_PRIOR_PARTS)

        if (recentSolved.isNotEmpty()) {
            appendLine("════ WHAT HAS BEEN WRITTEN SO FAR ════")
            appendLine("(Use these for naming, style, and interface consistency)")
            appendLine()
            recentSolved.forEach { solved ->
                appendLine("--- Part ${solved.subtask.index}: ${solved.subtask.title} ---")
                appendLine(solved.content.take(PART_SNIPPET_LEN))
                if (solved.content.length > PART_SNIPPET_LEN) appendLine("... (truncated)")
                appendLine()
            }
        }

        if (attemptNumber > 0 && lastError.isNotBlank()) {
            appendLine("════ RETRY — ATTEMPT ${attemptNumber + 1} ════")
            appendLine("Your previous attempt failed: $lastError")
            appendLine("Correct the issue and try again.")
            appendLine()
        }

        appendLine("════ YOUR JOB RIGHT NOW ════")
        appendLine("Write ONLY Part ${subtask.index} of the plan: \"${subtask.title}\"")
        appendLine()
        appendLine("Detail: ${subtask.detail.take(DETAIL_LIMIT)}")
        appendLine()
        appendLine("════ INSTRUCTIONS ════")
        appendLine(corePrompt)
        appendLine()
        appendLine("OUTPUT RULES:")
        appendLine("• Output ONLY the requested $fileExtension content.")
        appendLine("• Wrap it in a fenced code block: ```$fileExtension ... ```")
        appendLine("• No explanation, no prose before or after the code block.")
        appendLine("• Make it consistent with the parts already written above.")
    }

    // ── PUBLIC: build COMBINE prompt ──────────────────────────────
    fun buildCombinePrompt(
        taskDescription: String,
        taskIndex: Int,
        totalTasks: Int,
        outputFilename: String,
        fileExtension: String,
        planCard: String,
        solvedParts: List<SolvedSubTask>,
        corePrompt: String
    ): String = buildString {

        appendLine("════════════════════════════════════════")
        appendLine("MISSION BRIEF — read this fully before responding")
        appendLine("════════════════════════════════════════")
        appendLine()
        appendLine("YOU ARE: An AI assembly agent. Your job is to merge pre-written parts into one final file.")
        appendLine()
        appendLine("OVERALL GOAL (task ${taskIndex} of ${totalTasks}):")
        appendLine(taskDescription.take(TASK_DESC_LIMIT))
        appendLine()
        appendLine("OUTPUT FILE: $outputFilename")
        appendLine()

        if (planCard.isNotBlank()) {
            appendLine("════ ORIGINAL PLAN ════")
            appendLine(planCard.take(PLAN_CARD_LIMIT))
            appendLine()
        }

        appendLine("════ ALL PARTS TO MERGE ════")
        solvedParts.filter { it.success }.forEach { solved ->
            appendLine("--- Part ${solved.subtask.index}: ${solved.subtask.title} ---")
            appendLine(solved.content)
            appendLine()
        }

        appendLine("════ INSTRUCTIONS ════")
        appendLine(corePrompt)
        appendLine()
        appendLine("OUTPUT RULES:")
        appendLine("• Output ONLY the final merged $fileExtension content.")
        appendLine("• Wrap it in a fenced code block: ```$fileExtension ... ```")
        appendLine("• No explanation. No prose. Just the merged file.")
    }

    // ── PUBLIC: build PLAN REPAIR prompt ─────────────────────────
    fun buildPlanRepairPrompt(
        taskDescription: String,
        attemptNumber: Int,
        badReply: String
    ): String = buildString {

        appendLine("════════════════════════════════════════")
        appendLine("JSON FORMAT CORRECTION REQUIRED")
        appendLine("════════════════════════════════════════")
        appendLine()
        appendLine("Task: ${taskDescription.take(TASK_DESC_LIMIT)}")
        appendLine()

        when (attemptNumber) {
            0 -> {
                appendLine("Your previous reply could not be parsed as a JSON array.")
                appendLine("Return ONLY a raw JSON array. No markdown fences. No explanation.")
                appendLine()
                appendLine("Required format:")
                appendLine("[{\"title\":\"Step 1\",\"detail\":\"What to do\"},{\"title\":\"Step 2\",\"detail\":\"...\"}]")
            }
            1 -> {
                appendLine("Still not valid JSON. The reply must start with [ and end with ].")
                appendLine("Remove ALL text outside the array. No backticks. No prose.")
                appendLine()
                appendLine("Your bad reply started with:")
                appendLine(badReply.take(200))
                appendLine()
                appendLine("Fix it. Output the corrected JSON array only.")
            }
            else -> {
                appendLine("Final attempt. Copy this template exactly and fill in the values:")
                appendLine()
                appendLine("[")
                appendLine("  {\"title\": \"First step title\", \"detail\": \"What to do in this step\"},")
                appendLine("  {\"title\": \"Second step title\", \"detail\": \"What to do in this step\"}")
                appendLine("]")
                appendLine()
                appendLine("Replace the placeholder text with real content for task: ${taskDescription.take(200)}")
            }
        }
    }

    // ── PUBLIC: build compressed plan card ───────────────────────
    fun buildPlanCard(subtasks: List<SubTask>, currentIndex: Int? = null): String {
        val titleWidth = subtasks.maxOfOrNull { it.title.length }?.coerceAtMost(20) ?: 20
        return subtasks.joinToString("\n") { st ->
            val marker = if (st.index == currentIndex) "  ← YOU ARE HERE" else ""
            val paddedTitle = st.title.take(20).padEnd(titleWidth)
            val detail = st.detail.take(60).let { if (st.detail.length > 60) "$it…" else it }
            "[${st.index}] $paddedTitle — $detail$marker"
        }
    }

    // ── PUBLIC: build MetaAgent history block ─────────────────────
    fun buildMetaHistory(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) return ""
        val entries = if (history.size <= 18) {
            history
        } else {
            history.take(2) + history.takeLast(16)
        }
        return buildString {
            appendLine("--- CONVERSATION HISTORY ---")
            entries.forEach { (role, content) ->
                appendLine("${role.replaceFirstChar { it.uppercase() }}: $content")
                appendLine()
            }
        }
    }
}