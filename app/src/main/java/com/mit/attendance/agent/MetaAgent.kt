package com.mit.attendance.agent

import android.content.Context
import android.util.Log
import com.mit.attendance.service.ChatGptWebView
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MetaAgent"

// ─────────────────────────────────────────
//  META AGENT  v2 — stateless-first
// ─────────────────────────────────────────
class MetaAgent(
    private val context: Context,
    private val webView: ChatGptWebView,
    private val storageBaseDir: String
) {
    private val history = mutableListOf<Pair<String, String>>()
    private var sessionStarted = false

    suspend fun startSession(
        onReady: (greeting: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ startSession: starting...")
        try {
            webView.refresh()
            webView.awaitReady()
            history.clear()
            sessionStarted = true
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ startSession: ready in ${duration}ms")
            onReady("Hello! I'm your AI Agent builder. Type 'list all my remaining tasks' to see what's pending in chaipatti/tasks.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startSession: failed after ${System.currentTimeMillis() - startTime}ms: ${e.message}")
            onError("Meta-Agent failed to start: ${e.message}")
        }
    }

    suspend fun chat(userMessage: String): ChatResult = withContext(Dispatchers.Main) {
        if (!sessionStarted) error("Call startSession() first")

        val chatStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ chat: message received: \"${userMessage.take(50)}...\"")

        val prompt = buildFullPrompt(userMessage)

        val sendStartTime = System.currentTimeMillis()
        var currentReply = try {
            webView.send(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "❌ chat: Initial send failed after ${System.currentTimeMillis() - sendStartTime}ms: ${e.message}")
            return@withContext ChatResult("Error: ${e.message}", false, null)
        }
        Log.d(TAG, "✅ chat: Initial reply received in ${System.currentTimeMillis() - sendStartTime}ms")

        val loopHistory = mutableListOf<Pair<String, String>>()

        commandLoop@ for (i in 0 until 5) {
            val raw   = currentReply.trim()
            val clean = raw.removeSurrounding("```").removeSurrounding("`").trim().lowercase()

            val fsResult: String = when {
                clean == "ls" -> {
                    Log.d(TAG, "📂 chat: processing 'ls' command")
                    val tasks = OutputDirectoryManager.listPendingTasks(context)
                    if (tasks.isEmpty()) "The 'chaipatti/tasks' folder is empty."
                    else "Files in 'chaipatti/tasks':\n" + tasks.joinToString("\n") { "- ${it.name}" }
                }
                clean == "ls -r" -> {
                    Log.d(TAG, "📂 chat: processing 'ls -r' command")
                    OutputDirectoryManager.listRecursive(context)
                }
                clean.startsWith("cat ") -> {
                    val filename = clean.substring(4).trim()
                    Log.d(TAG, "📂 chat: processing 'cat $filename' command")
                    OutputDirectoryManager.readFile(context, filename)
                }
                clean.startsWith("count ") -> {
                    val filename = clean.substring(6).trim()
                    Log.d(TAG, "📂 chat: processing 'count $filename' command")
                    OutputDirectoryManager.countLines(context, filename)
                }
                else -> break@commandLoop
            }

            loopHistory.add("assistant" to raw)
            val systemMsg = "SYSTEM: $fsResult"

            val cmdSendStartTime = System.currentTimeMillis()
            val next = try { webView.send(systemMsg) }
            catch (e: Exception) {
                Log.e(TAG, "❌ chat: Command loop send failed after ${System.currentTimeMillis() - cmdSendStartTime}ms: ${e.message}")
                break@commandLoop
            }
            Log.d(TAG, "✅ chat: Command loop reply received in ${System.currentTimeMillis() - cmdSendStartTime}ms")

            loopHistory.add("user" to systemMsg)
            currentReply = next
        }

        history.add("user" to userMessage)
        history.addAll(loopHistory)
        history.add("assistant" to currentReply)

        val result = processReplyWithRepair(currentReply)
        Log.d(TAG, "🏁 chat: completed in ${System.currentTimeMillis() - chatStartTime}ms")
        result
    }

    private suspend fun processReplyWithRepair(reply: String): ChatResult {
        val repairStartTime = System.currentTimeMillis()
        val trimmed = reply.trim().removeSurrounding("```").removeSurrounding("```json").trim()
        val startMarker = "agent_config"
        
        val hasMarker = trimmed.contains(startMarker, ignoreCase = true)
        val isExplicit = trimmed.startsWith(startMarker, ignoreCase = true) && trimmed.endsWith("}")

        if (!hasMarker) return ChatResult(reply, false, null)

        val startIndex = reply.indexOf(startMarker, ignoreCase = true)
        val remaining  = reply.substring(startIndex + startMarker.length)
        val firstBrace = remaining.indexOf('{')
        val lastBrace  = remaining.lastIndexOf('}')

        if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
            if (isExplicit) Log.w(TAG, "⚠️ processReplyWithRepair: Marker found but braces missing.")
            return ChatResult(reply + "\n\n⚠️ Config block incomplete.", false, null)
        }

        val jsonRaw = remaining.substring(firstBrace, lastBrace + 1).trim()
        val json    = jsonRaw.replace("\"AGENT_OUTPUT_DIR\"", "\"$storageBaseDir/agent_output\"")

        return try {
            val config = AgentConfig.fromJson(json)
            Log.d(TAG, "✅ processReplyWithRepair: Config parsed successfully in ${System.currentTimeMillis() - repairStartTime}ms")
            ChatResult(reply, true, config)
        } catch (e: Exception) {
            Log.w(TAG, "🛠️ processReplyWithRepair: Config JSON malformed — attempting repair: ${e.message}")

            val repairPrompt = """
Your previous response contained an agent_config block with malformed JSON.
The JSON parser reported: ${e.message}

The bad JSON was:
$jsonRaw

Please output ONLY the corrected JSON object (no markdown fences, no explanation):
{
  "taskDescription": "...",
  "fileExtension": "...",
  "fileNameTemplate": "...",
  "outputDirectory": "AGENT_OUTPUT_DIR",
  "totalTasks": 1,
  "planPrompt": "...",
  "solvePrompt": "...",
  "combinePrompt": "..."
}
            """.trimIndent()

            val repairSendStartTime = System.currentTimeMillis()
            val repairReply = try { webView.send(repairPrompt) }
            catch (ex: Exception) { 
                Log.e(TAG, "❌ processReplyWithRepair: Repair send failed after ${System.currentTimeMillis() - repairSendStartTime}ms")
                return ChatResult(reply + "\n\n⚠️ Config JSON malformed.", false, null) 
            }
            Log.d(TAG, "✅ processReplyWithRepair: Repair reply received in ${System.currentTimeMillis() - repairSendStartTime}ms")

            history.add("user" to repairPrompt)
            history.add("assistant" to repairReply)

            val repairedJson = repairReply
                .replace(Regex("(?i)```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
                .replace("\"AGENT_OUTPUT_DIR\"", "\"$storageBaseDir/agent_output\"")

            return try {
                val config = AgentConfig.fromJson(repairedJson)
                Log.d(TAG, "✅ processReplyWithRepair: Config repaired and parsed successfully")
                ChatResult(repairReply, true, config)
            } catch (e2: Exception) {
                Log.e(TAG, "❌ processReplyWithRepair: Repair failed to produce valid JSON")
                ChatResult(reply + "\n\n⚠️ Config JSON malformed (repair failed).", false, null)
            }
        }
    }

    private fun buildFullPrompt(latestUserMessage: String): String = buildString {
        appendLine("[SYSTEM]")
        appendLine(AgentConfig.META_AGENT_SYSTEM_PROMPT)
        appendLine()
        val historyBlock = ContextMemory.buildMetaHistory(history)
        if (historyBlock.isNotBlank()) appendLine(historyBlock)
        appendLine("User: $latestUserMessage")
        appendLine("Assistant:")
    }

    fun reset() { 
        Log.d(TAG, "🔄 reset: history cleared")
        history.clear() 
    }

    data class ChatResult(val reply: String, val configReady: Boolean, val config: AgentConfig?)
}
