package com.mit.attendance.agent

import android.util.Log
import com.mit.attendance.service.ChatGptWebView

private const val TAG = "OutputAnalyzer"

// ─────────────────────────────────────────
//  OUTPUT ANALYZER  — v1
//
//  After every successful task, this class:
//    [1] EXPLAINS  — what was actually built (structure, purpose, key decisions)
//    [2] COMPARES  — the output against the original user requirement
//    [3] GAPS      — lists anything the user asked for that wasn't covered
//    [4] EXTRAS    — lists anything added beyond what was asked
//
//  This runs as a SEPARATE fresh session so it doesn't interfere
//  with the worker's WebView state.
//
//  The result is a structured AnalysisReport that the UI can display
//  as a human-readable summary card after each task completes.
// ─────────────────────────────────────────
class OutputAnalyzer(private val webView: ChatGptWebView) {

    // ── ANALYSIS RESULT ───────────────────────────────────────────
    data class AnalysisReport(
        val taskDescription: String,
        val explanation: String,       // What was built
        val requirementsMet: List<String>,   // ✅ things the output satisfies
        val gaps: List<String>,              // ❌ things asked for but missing
        val extras: List<String>,            // ➕ things added beyond the ask
        val lineCount: Int,
        val verdict: Verdict
    ) {
        enum class Verdict {
            FULLY_MET,          // Output matches the requirement completely
            MOSTLY_MET,         // Minor gaps only
            PARTIALLY_MET,      // Significant gaps
            OVER_ENGINEERED,    // Output is much larger/more complex than asked
            NEEDS_REVIEW        // Analyzer couldn't determine clearly
        }

        fun toReadableString(): String = buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📋 OUTPUT ANALYSIS")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("📦 WHAT WAS BUILT ($lineCount lines)")
            appendLine(explanation)
            appendLine()

            if (requirementsMet.isNotEmpty()) {
                appendLine("✅ REQUIREMENTS MET")
                requirementsMet.forEach { appendLine("  • $it") }
                appendLine()
            }

            if (gaps.isNotEmpty()) {
                appendLine("❌ GAPS (asked for, not delivered)")
                gaps.forEach { appendLine("  • $it") }
                appendLine()
            }

            if (extras.isNotEmpty()) {
                appendLine("➕ EXTRAS (added beyond the ask)")
                extras.forEach { appendLine("  • $it") }
                appendLine()
            }

            appendLine("🏁 VERDICT: ${verdict.name.replace('_', ' ')}")
        }
    }

    // ── ANALYZE ───────────────────────────────────────────────────
    /**
     * Sends the finished output + original requirement to a fresh
     * ChatGPT session and asks for a structured analysis.
     *
     * [customPrompt] — optional override from AgentConfig.outputAnalysisPrompt.
     * If blank, the default analysis prompt is used.
     *
     * Returns null if the analysis fails (network error, parse error) —
     * caller should treat null as "analysis unavailable, not a fatal error".
     */
    suspend fun analyze(
        taskDescription: String,
        finishedContent: String,
        fileExtension: String,
        customPrompt: String = ""
    ): AnalysisReport? {
        val analysisStartTime = System.currentTimeMillis()
        Log.d(TAG, "🚀 analyze: Starting analysis for task: ${taskDescription.take(50)}...")
        val lineCount = finishedContent.lines().size

        val prompt = buildAnalysisPrompt(
            taskDescription  = taskDescription,
            finishedContent  = finishedContent,
            fileExtension    = fileExtension,
            lineCount        = lineCount,
            customPrompt     = customPrompt
        )

        val sendStartTime = System.currentTimeMillis()
        val reply = try {
            webView.send(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "❌ analyze: Send failed after ${System.currentTimeMillis() - sendStartTime}ms: ${e.message}")
            return null
        }
        Log.d(TAG, "✅ analyze: Reply received in ${System.currentTimeMillis() - sendStartTime}ms")

        val result = parseAnalysisReply(reply, taskDescription, lineCount)
        Log.d(TAG, "🏁 analyze: Completed in ${System.currentTimeMillis() - analysisStartTime}ms. Verdict: ${result.verdict}")
        return result
    }

    // ── PROMPT BUILDER ────────────────────────────────────────────
    private fun buildAnalysisPrompt(
        taskDescription: String,
        finishedContent: String,
        fileExtension: String,
        lineCount: Int,
        customPrompt: String
    ): String = buildString {

        appendLine("════════════════════════════════════════")
        appendLine("OUTPUT ANALYSIS TASK")
        appendLine("════════════════════════════════════════")
        appendLine()
        appendLine("You are a senior code/content reviewer. Your job is to:")
        appendLine("  1. EXPLAIN what was built (briefly, 2-4 sentences)")
        appendLine("  2. COMPARE it to what was originally asked")
        appendLine("  3. LIST what requirements were met")
        appendLine("  4. LIST what gaps exist (asked for but missing)")
        appendLine("  5. LIST any extras added beyond the ask")
        appendLine("  6. Give a verdict")
        appendLine()
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("ORIGINAL REQUIREMENT:")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine(taskDescription)
        appendLine()
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("FINISHED OUTPUT ($lineCount lines of .$fileExtension):")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        // Truncate very large outputs to avoid prompt overflow
        val contentToShow = if (finishedContent.length > 8_000)
            finishedContent.take(8_000) + "\n... (truncated for analysis)"
        else finishedContent
        appendLine(contentToShow)
        appendLine()

        if (customPrompt.isNotBlank()) {
            appendLine("ADDITIONAL ANALYSIS INSTRUCTIONS:")
            appendLine(customPrompt)
            appendLine()
        }

        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("RESPOND IN THIS EXACT FORMAT (no markdown, no fences):")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("EXPLANATION:")
        appendLine("<2-4 sentences describing what was built and how it works>")
        appendLine()
        appendLine("REQUIREMENTS_MET:")
        appendLine("- <item>")
        appendLine("- <item>")
        appendLine()
        appendLine("GAPS:")
        appendLine("- <item or 'none'>")
        appendLine()
        appendLine("EXTRAS:")
        appendLine("- <item or 'none'>")
        appendLine()
        appendLine("VERDICT: <one of: FULLY_MET | MOSTLY_MET | PARTIALLY_MET | OVER_ENGINEERED | NEEDS_REVIEW>")
        appendLine()
        appendLine("VERDICT_REASON:")
        appendLine("<one sentence explaining the verdict>")
    }

    // ── REPLY PARSER ──────────────────────────────────────────────
    private fun parseAnalysisReply(
        reply: String,
        taskDescription: String,
        lineCount: Int
    ): AnalysisReport {
        // Graceful parser — picks out each section by its header label.
        // If a section is missing, it returns a safe default rather than crashing.

        fun extractSection(text: String, header: String, nextHeaders: List<String>): String {
            val startIdx = text.indexOf(header, ignoreCase = true)
            if (startIdx == -1) return ""
            val contentStart = startIdx + header.length
            val endIdx = nextHeaders
                .mapNotNull { nh -> text.indexOf(nh, contentStart).takeIf { it > contentStart } }
                .minOrNull() ?: text.length
            return text.substring(contentStart, endIdx).trim()
        }

        fun parseBulletList(block: String): List<String> =
            block.lines()
                .map { it.trimStart('-', '•', '*', ' ').trim() }
                .filter { it.isNotBlank() && it.lowercase() != "none" }

        val allHeaders = listOf("EXPLANATION:", "REQUIREMENTS_MET:", "GAPS:", "EXTRAS:",
            "VERDICT:", "VERDICT_REASON:")

        val explanation = extractSection(reply, "EXPLANATION:", allHeaders.drop(1))
            .ifBlank { "No explanation available." }

        val metBlock  = extractSection(reply, "REQUIREMENTS_MET:", allHeaders.drop(2))
        val gapsBlock = extractSection(reply, "GAPS:", allHeaders.drop(3))
        val extrasBlock = extractSection(reply, "EXTRAS:", allHeaders.drop(4))

        val verdictRaw = extractSection(reply, "VERDICT:", allHeaders.drop(5))
            .lines().firstOrNull()?.trim()?.uppercase() ?: "NEEDS_REVIEW"

        val verdict = try {
            AnalysisReport.Verdict.valueOf(
                verdictRaw.replace(' ', '_').replace(Regex("[^A-Z_]"), "")
            )
        } catch (_: Exception) {
            AnalysisReport.Verdict.NEEDS_REVIEW
        }

        return AnalysisReport(
            taskDescription  = taskDescription,
            explanation      = explanation,
            requirementsMet  = parseBulletList(metBlock),
            gaps             = parseBulletList(gapsBlock),
            extras           = parseBulletList(extrasBlock),
            lineCount        = lineCount,
            verdict          = verdict
        )
    }
}
