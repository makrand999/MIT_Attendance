package com.mit.attendance.agent

import android.content.Context
import android.util.Log
import com.mit.attendance.service.ChatGptWebView
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

private const val TAG = "WorkerAgent"

// ─────────────────────────────────────────
//  WORKER AGENT  — v3 (CLEAN OUTPUT)
// ─────────────────────────────────────────
class WorkerAgent(private val context: Context, private val webView: ChatGptWebView) {

    private var cancelled = false
    private var planCard    = ""
    private var subtaskList = emptyList<SubTask>()
    private var sessionTurnCount = 0

    private val outputAnalyzer = OutputAnalyzer(webView)

    private companion object {
        const val MAX_PLAN_RETRIES  = 3
        const val MAX_SOLVE_RETRIES = 3
        const val COMMAND_LOOP_MAX  = 5
        const val SUBTASK_DELAY_MS  = 400L
        const val TASK_DELAY_MS     = 600L
        const val WARMUP_MS         = 2_500L
        const val MAX_TURNS_PER_SESSION = 5

        val ERROR_SIGNATURES = listOf(
            "something went wrong", "too many requests", "rate limit",
            "502 bad gateway", "504 gateway", "service unavailable",
            "please try again", "network error", "<!doctype html", "<html"
        )
    }

    suspend fun run(
        config: AgentConfig,
        onProgress: (ProgressEvent) -> Unit,
        onComplete: (RunSummary) -> Unit
    ) = withContext(Dispatchers.IO) {
        val runStartTime = System.currentTimeMillis()
        Log.d(TAG, "🚀 run: Starting agent run for task: ${config.taskDescription}")
        cancelled = false
        File(config.outputDirectory).mkdirs()
        File(config.outputDirectory, "parts").mkdirs()

        val checkpoint = CheckpointManager(config.outputDirectory)
        val cpState    = checkpoint.load() ?: CheckpointManager.CheckpointState(config.toJson())
        val dedupe     = DedupeTracker(initialValues = cpState.usedTopics, enabled = config.dedupeField != null)
        val remaining  = cpState.remainingTasks(config.totalTasks)

        if (cpState.completedCount > 0)
            notifyProgress(onProgress, ProgressEvent.Resumed(cpState.completedCount, config.totalTasks, remaining.size))

        val results = mutableListOf<TaskResult>()

        for (taskIndex in remaining) {
            if (cancelled) {
                Log.d(TAG, "⏹️ run: Cancelled by user")
                break
            }
            val taskStartTime = System.currentTimeMillis()
            val filename = config.fileNameTemplate.replace("{index}", taskIndex.toString().padStart(3, '0'))
            val task = TopLevelTask(index = taskIndex, filename = filename, filePath = "${config.outputDirectory}/$filename")
            
            Log.d(TAG, "⏱️ run: Starting Top-Level Task $taskIndex: $filename")
            notifyProgress(onProgress, ProgressEvent.TaskStarted(taskIndex, config.totalTasks, cpState.completedCount + results.size, filename))

            val result = runTaskPipeline(task, config, dedupe, onProgress)
            results.add(result)

            if (result.success) {
                cpState.completedTasks.add(taskIndex)
                cpState.usedTopics.addAll(dedupe.snapshot().drop(cpState.usedTopics.size))
                checkpoint.saveAtomic(cpState)
                dedupe.registerFromTask(task)

                File(config.outputDirectory, ".task_${taskIndex}_partials.json").delete()
                cleanupIntermediateFiles(config.outputDirectory, task.filename, config.fileExtension)

                if (task.finalContent.isNotBlank()) {
                    val analysisStartTime = System.currentTimeMillis()
                    Log.d(TAG, "🔍 run: Starting output analysis for $filename")
                    notifyProgress(onProgress, ProgressEvent.AnalysisStarted(taskIndex, task.filename))
                    val report = try {
                        withContext(Dispatchers.Main) {
                            webView.refresh(); delay(WARMUP_MS); sessionTurnCount = 0
                            outputAnalyzer.analyze(
                                taskDescription = config.taskDescription,
                                finishedContent = task.finalContent,
                                fileExtension   = config.fileExtension,
                                customPrompt    = config.outputAnalysisPrompt
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "❌ run: OutputAnalyzer failed: ${e.message}"); null }

                    Log.d(TAG, "✅ run: Analysis completed in ${System.currentTimeMillis() - analysisStartTime}ms")
                    notifyProgress(onProgress, ProgressEvent.OutputAnalyzed(taskIndex, task.filename,
                        report ?: OutputAnalyzer.AnalysisReport(
                            taskDescription = config.taskDescription,
                            explanation     = "Analysis unavailable (session error).",
                            requirementsMet = emptyList(), gaps = emptyList(), extras = emptyList(),
                            lineCount       = task.finalContent.lines().size,
                            verdict         = OutputAnalyzer.AnalysisReport.Verdict.NEEDS_REVIEW
                        )
                    ))
                }
            }

            val taskDuration = System.currentTimeMillis() - taskStartTime
            Log.d(TAG, "🏁 run: Task $taskIndex finished in ${taskDuration}ms. Success: ${result.success}")
            notifyProgress(onProgress, ProgressEvent.TaskFinished(taskIndex, config.totalTasks, cpState.completedTasks.size, result))
            delay(TASK_DELAY_MS)
        }

        val allDone = cpState.completedTasks.size == config.totalTasks
        if (allDone) checkpoint.clear()

        val finalFiles = results.filter { it.success }.flatMap { it.finalFilePaths }
        val runDuration = System.currentTimeMillis() - runStartTime
        Log.d(TAG, "🏁 run: Full run completed in ${runDuration}ms. Succeeded: ${cpState.completedTasks.size}/${config.totalTasks}")

        withContext(Dispatchers.Main) {
            onComplete(RunSummary(
                total          = config.totalTasks,
                succeeded      = cpState.completedTasks.size,
                failedThisRun  = results.count { !it.success },
                results        = results,
                checkpointPath = if (!allDone) "${config.outputDirectory}/.checkpoint.json" else null,
                finalFiles     = finalFiles
            ))
        }
    }

    private fun cleanupIntermediateFiles(outputDir: String, filename: String, ext: String) {
        try {
            val partsDir = File(outputDir, "parts")
            if (!partsDir.exists()) return
            val pattern = Regex("""_s\d+\.$ext$""")
            partsDir.listFiles()
                ?.filter { pattern.containsMatchIn(it.name) }
                ?.forEach { file -> file.delete(); Log.d(TAG, "🗑️ cleanupIntermediateFiles: Cleaned: ${file.name}") }
            if (partsDir.listFiles()?.isEmpty() == true) {
                partsDir.delete()
                Log.d(TAG, "🗑️ cleanupIntermediateFiles: Removed empty parts/")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ cleanupIntermediateFiles: Cleanup warning: ${e.message}")
        }
    }

    private suspend fun runTaskPipeline(
        task: TopLevelTask,
        config: AgentConfig,
        dedupe: DedupeTracker,
        onProgress: (ProgressEvent) -> Unit
    ): TaskResult {
        planCard = ""; subtaskList = emptyList()

        val planStartTime = System.currentTimeMillis()
        task.stage = TopLevelTask.Stage.PLAN
        Log.d(TAG, "📋 runTaskPipeline: Stage -> PLAN")
        notifyProgress(onProgress, ProgressEvent.StageChanged(task.index, task.filename, task.stage))

        val fullPlanPrompt = ContextMemory.buildPlanPrompt(
            taskDescription   = config.taskDescription,
            taskIndex         = task.index,
            totalTasks        = config.totalTasks,
            dedupeInstruction = dedupe.inject("{done_items}"),
            corePrompt        = config.planPrompt.replace("{task}", config.taskDescription)
        )

        val planReply = smartSend(fullPlanPrompt, forceRefresh = true)
            ?: return TaskResult(task, false, "PLAN send failed", emptyList())

        val (subtasks, manifest) = parsePlanWithRetry(planReply, config)
            ?: return TaskResult(task, false, "PLAN parse failed after $MAX_PLAN_RETRIES retries", emptyList())

        task.subtasks    = subtasks
        task.planManifest = manifest
        subtaskList      = subtasks
        planCard         = ContextMemory.buildPlanCard(subtasks)
        if (subtasks.isNotEmpty() && config.dedupeField != null)
            task.chosenDedupeValue = subtasks.first().dedupeValue

        Log.d(TAG, "✅ runTaskPipeline: PLAN complete in ${System.currentTimeMillis() - planStartTime}ms. Subtasks: ${subtasks.size}")
        notifyProgress(onProgress, ProgressEvent.Planned(
            task.index, task.filename, subtasks.size,
            task.chosenDedupeValue, manifest
        ))

        val solveStartTime = System.currentTimeMillis()
        task.stage = TopLevelTask.Stage.SOLVE
        Log.d(TAG, "✍️ runTaskPipeline: Stage -> SOLVE")
        notifyProgress(onProgress, ProgressEvent.StageChanged(task.index, task.filename, task.stage))

        val partialsFile = File(config.outputDirectory, ".task_${task.index}_partials.json")
        val partials     = loadPartials(partialsFile)

        for (subtask in subtasks) {
            if (cancelled) break

            val recovered = partials[subtask.index.toString()]
            if (recovered != null) {
                Log.d(TAG, "♻️ runTaskPipeline: Recovered subtask ${subtask.index}")
                val recoveredPath = resolveOutputPath(config.outputDirectory, task, subtask, config, manifest)
                task.solved.add(SolvedSubTask(subtask, recovered, recoveredPath, true))
                continue
            }

            Log.d(TAG, "⏱️ runTaskPipeline: Solving subtask ${subtask.index}/${subtasks.size}: ${subtask.title}")
            notifyProgress(onProgress, ProgressEvent.SubTaskStarted(task.index, subtask.index, subtasks.size, subtask.title))
            val solved = solveSubTask(task, subtask, config, onProgress)
            task.solved.add(solved)

            if (solved.success) {
                partials[subtask.index.toString()] = solved.content
                savePartials(partialsFile, partials)

                if (manifest.outputMode != PlanManifest.OutputMode.SINGLE_FILE) {
                    val outPath = resolveOutputPath(config.outputDirectory, task, subtask, config, manifest)
                    val result  = FileHandler.create(outPath, solved.content)
                    if (result.success) {
                        task.outputFilePaths.add(outPath)
                        Log.d(TAG, "📄 runTaskPipeline: Written part: ${File(outPath).name}")
                    }
                }
            }
            delay(SUBTASK_DELAY_MS)
        }
        Log.d(TAG, "✅ runTaskPipeline: SOLVE phase finished in ${System.currentTimeMillis() - solveStartTime}ms")

        val successfulParts = task.solved.filter { it.success }
        if (successfulParts.isEmpty()) return TaskResult(task, false, "All subtasks failed", emptyList())

        return when (manifest.outputMode) {

            PlanManifest.OutputMode.SINGLE_FILE -> {
                val combineStartTime = System.currentTimeMillis()
                val finalPath = resolveSingleFilePath(config, task, subtasks, manifest)

                if (config.hasCombineStep && successfulParts.size > 1) {
                    task.stage = TopLevelTask.Stage.COMBINE
                    Log.d(TAG, "🔗 runTaskPipeline: Stage -> COMBINE")
                    notifyProgress(onProgress, ProgressEvent.StageChanged(task.index, task.filename, task.stage))

                    val combinePrompt = ContextMemory.buildCombinePrompt(
                        taskDescription = config.taskDescription, taskIndex = task.index,
                        totalTasks      = config.totalTasks, outputFilename = File(finalPath).name,
                        fileExtension   = config.fileExtension, planCard = planCard,
                        solvedParts     = successfulParts,
                        corePrompt      = config.combinePrompt
                            .replace("{task}", config.taskDescription)
                            .replace("{output_file}", File(finalPath).name)
                            .replace("{parts_content}", "")
                    )
                    val combineReply = smartSend(combinePrompt, forceRefresh = true)
                        ?: return TaskResult(task, false, "COMBINE failed", emptyList())
                    val parsed = CodeBlockParser.extract(combineReply, config.fileExtension)
                    task.finalContent = if (parsed.found) parsed.content else combineReply.trim()
                } else {
                    task.finalContent = successfulParts.joinToString("\n\n") { it.content }
                }

                val fileResult = FileHandler.create(finalPath, task.finalContent)
                Log.d(TAG, "✅ runTaskPipeline: COMBINE/Write finished in ${System.currentTimeMillis() - combineStartTime}ms")
                task.stage = if (fileResult.success) TopLevelTask.Stage.DONE else TopLevelTask.Stage.FAILED
                TaskResult(task, fileResult.success, fileResult.message,
                    if (fileResult.success) listOf(finalPath) else emptyList())
            }

            PlanManifest.OutputMode.MULTI_FILE,
            PlanManifest.OutputMode.FREE -> {
                task.stage = TopLevelTask.Stage.DONE
                val writtenFiles = task.outputFilePaths.filter { File(it).exists() }
                if (writtenFiles.isEmpty()) {
                    successfulParts.forEach { solved ->
                        val path = resolveOutputPath(config.outputDirectory, task, solved.subtask, config, manifest)
                        val r = FileHandler.create(path, solved.content)
                        if (r.success) task.outputFilePaths.add(path)
                    }
                }
                val finalFiles = task.outputFilePaths.filter { File(it).exists() }
                Log.d(TAG, "✅ runTaskPipeline: Multi-file task done: ${finalFiles.size} files written")
                TaskResult(task, finalFiles.isNotEmpty(), "${finalFiles.size} files written", finalFiles)
            }
        }
    }

    private fun resolveOutputPath(outputDir: String, task: TopLevelTask, subtask: SubTask, config: AgentConfig, manifest: PlanManifest): String {
        val name = when {
            !subtask.path.isNullOrBlank() -> subtask.path.trim().replace("..", "").trimStart('/')
            subtask.title.contains('.') || subtask.title.equals("Makefile", ignoreCase = true) -> subtask.title.trim()
            else -> {
                val base = task.filename.removeSuffix(".${config.fileExtension}")
                "${base}_${subtask.index}.${config.fileExtension}"
            }
        }
        return "$outputDir/$name"
    }

    private fun resolveSingleFilePath(config: AgentConfig, task: TopLevelTask, subtasks: List<SubTask>, manifest: PlanManifest): String {
        val lastPath = subtasks.lastOrNull()?.path?.takeIf { it.isNotBlank() }
        if (lastPath != null) return "${config.outputDirectory}/$lastPath"
        val suggested = manifest.suggestedStructure.trim()
        if (suggested.isNotBlank() && !suggested.contains(',') && suggested.contains('.')) return "${config.outputDirectory}/$suggested"
        return task.filePath
    }

    private suspend fun solveSubTask(
        task: TopLevelTask,
        subtask: SubTask,
        config: AgentConfig,
        onProgress: (ProgressEvent) -> Unit
    ): SolvedSubTask {
        val subTaskStartTime = System.currentTimeMillis()
        val partPath  = "${config.outputDirectory}/parts/${task.filename.replace(".${config.fileExtension}", "_s${subtask.index}.${config.fileExtension}")}"
        var lastError = "Empty response"

        repeat(MAX_SOLVE_RETRIES) { attempt ->
            if (cancelled) return SolvedSubTask(subtask, "", partPath, false, "Cancelled")
            Log.d(TAG, "⏱️ solveSubTask: Attempt ${attempt + 1} for subtask ${subtask.index}")

            val corePrompt = config.solvePrompt
                .replace("{task}", config.taskDescription)
                .replace("{subtask_index}", subtask.index.toString())
                .replace("{subtask_title}", subtask.title)
                .replace("{subtask_detail}", subtask.detail)
                .replace("{subtask_json}", subtask.rawJson)
                .replace("{full_plan}", planCard)
                .replace("{prior_parts}", buildPriorPartsText(task.solved))
                .replace("{output_file}", task.filename)

            val fullPrompt = ContextMemory.buildSolvePrompt(
                taskDescription = config.taskDescription, taskIndex = task.index, totalTasks = config.totalTasks,
                outputFilename  = task.filename, fileExtension = config.fileExtension,
                planCard        = ContextMemory.buildPlanCard(subtaskList, subtask.index),
                solvedParts     = task.solved.filter { it.success },
                subtask         = subtask, corePrompt = corePrompt,
                attemptNumber   = attempt, lastError = lastError
            )

            val firstReply = smartSend(fullPrompt, forceRefresh = (attempt > 0))
            if (firstReply == null) { 
                lastError = "No reply (attempt ${attempt + 1})"
                Log.w(TAG, "⚠️ solveSubTask: $lastError")
                return@repeat 
            }
            if (isErrorReply(firstReply)) {
                lastError = "Error page (attempt ${attempt + 1}): ${firstReply.take(80)}"
                Log.w(TAG, "⚠️ solveSubTask: $lastError")
                delay(2_000L * (attempt + 1)); return@repeat
            }

            val fullContent = continuationLoop(firstReply, config.fileExtension)
            val content = CodeBlockParser.extract(fullContent, config.fileExtension)
                .let { if (it.found) it.content else fullContent.trim() }

            if (content.isNotEmpty()) {
                val abstractStartTime = System.currentTimeMillis()
                val abstract = generateAbstract()
                Log.d(TAG, "✅ solveSubTask: Abstract generated in ${System.currentTimeMillis() - abstractStartTime}ms")
                
                val solved = SolvedSubTask(subtask, content, partPath, true, abstract = abstract)
                Log.d(TAG, "✅ solveSubTask: Subtask ${subtask.index} solved in ${System.currentTimeMillis() - subTaskStartTime}ms")
                notifyProgress(onProgress, ProgressEvent.SubTaskDone(task.index, subtask.index, task.subtasks.size, true))
                return solved
            }
            lastError = "Empty content (attempt ${attempt + 1})"
            Log.w(TAG, "⚠️ solveSubTask: $lastError")
        }

        // All retries failed
        withContext(Dispatchers.Main) {
            Log.d(TAG, "❌ solveSubTask: All retries exhausted. Clearing cookies for a fresh start.")
            webView.clearCookies()
        }

        notifyProgress(onProgress, ProgressEvent.SubTaskDone(task.index, subtask.index, task.subtasks.size, false))
        return SolvedSubTask(subtask, "", partPath, false, lastError)
    }

    private suspend fun continuationLoop(
        firstReply: String,
        fileExtension: String,
        maxContinuations: Int = 5
    ): String = withContext(Dispatchers.Main) {
        val loopStartTime = System.currentTimeMillis()
        var accumulated = firstReply
        var prevLength  = 0

        repeat(maxContinuations) { iteration ->
            if (cancelled) return@withContext accumulated
            Log.d(TAG, "⏱️ continuationLoop: Check iteration ${iteration + 1}")

            val checkReply = try {
                webView.send("Did you finish writing everything for this part, or did you stop early? Reply with just: DONE or CONTINUE")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ continuationLoop: Check failed: ${e.message}")
                return@withContext accumulated
            }
            sessionTurnCount++

            val done = checkReply.trim().uppercase().let {
                it.startsWith("DONE") || (!it.startsWith("CONTINUE") && it.length < 20)
            }
            if (done) {
                Log.d(TAG, "✅ continuationLoop: LLM signalled DONE in ${System.currentTimeMillis() - loopStartTime}ms")
                return@withContext accumulated
            }

            Log.d(TAG, "🔄 continuationLoop: Nudging LLM to CONTINUE (iteration ${iteration + 1})")
            val chunkStartTime = System.currentTimeMillis()
            val chunk = try {
                webView.send("Continue exactly where you left off. Output only the remaining code, fenced in ```$fileExtension.")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ continuationLoop: Send failed: ${e.message}")
                return@withContext accumulated
            }
            sessionTurnCount++
            Log.d(TAG, "✅ continuationLoop: Chunk received in ${System.currentTimeMillis() - chunkStartTime}ms")

            if (isErrorReply(chunk)) return@withContext accumulated

            val chunkContent = CodeBlockParser.extract(chunk, fileExtension)
            val newText = if (chunkContent.found) chunkContent.content else chunk.trim()

            if (newText.isBlank() || newText.length == prevLength) return@withContext accumulated

            prevLength  = newText.length
            accumulated = mergeChunk(accumulated, newText, fileExtension)
        }
        accumulated
    }

    private fun mergeChunk(accumulated: String, chunk: String, ext: String): String {
        val accTrimmed   = accumulated.trimEnd()
        val chunkTrimmed = chunk.trim()
        return if (accTrimmed.endsWith("```")) {
            accTrimmed.dropLast(3).trimEnd() + "\n" + chunkTrimmed.removePrefix("```$ext").removePrefix("```").trimStart()
        } else {
            "$accTrimmed\n$chunkTrimmed"
        }
    }

    private suspend fun generateAbstract(): String = withContext(Dispatchers.Main) {
        return@withContext try {
            val reply = webView.send("In 2–3 sentences, summarise what you just wrote: what it contains, the key function/type names, and what the next part needs to know to build on it. No code. Plain English only.")
            sessionTurnCount++
            reply.trim().take(400)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ generateAbstract: failed: ${e.message}")
            ""
        }
    }

    private suspend fun parsePlanWithRetry(firstReply: String, config: AgentConfig): Pair<List<SubTask>, PlanManifest>? {
        runCatching { SubTask.parseFromPlanReply(firstReply, config.dedupeField) }.onSuccess { return it }
        var badReply = firstReply
        repeat(MAX_PLAN_RETRIES) { attempt ->
            Log.w(TAG, "⚠️ parsePlanWithRetry: Repair attempt ${attempt + 1}")
            val repairReply = smartSend(ContextMemory.buildPlanRepairPrompt(config.taskDescription, attempt, badReply), forceRefresh = true) ?: return null
            badReply = repairReply
            runCatching { SubTask.parseFromPlanReply(repairReply, config.dedupeField) }.onSuccess { return it }
        }

        // All retries failed
        withContext(Dispatchers.Main) {
            Log.d(TAG, "❌ parsePlanWithRetry: All retries exhausted. Clearing cookies for a fresh start.")
            webView.clearCookies()
        }
        return null
    }

    private suspend fun smartSend(prompt: String, forceRefresh: Boolean = false): String? = try {
        withContext(Dispatchers.Main) {
            if (forceRefresh || sessionTurnCount >= MAX_TURNS_PER_SESSION || !webView.isReady) {
                Log.d(TAG, "🔄 smartSend: Refreshing WebView (Turns: $sessionTurnCount, Force: $forceRefresh)")
                val refreshStartTime = System.currentTimeMillis()
                webView.refresh(); delay(WARMUP_MS); sessionTurnCount = 0
                Log.d(TAG, "✅ smartSend: Refresh completed in ${System.currentTimeMillis() - refreshStartTime}ms")
            }
            val sendStartTime = System.currentTimeMillis()
            val reply = handleCommands(webView.send(prompt))
            sessionTurnCount++
            Log.d(TAG, "✅ smartSend: Reply received in ${System.currentTimeMillis() - sendStartTime}ms")
            reply
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ smartSend failed: ${e.message}"); sessionTurnCount = MAX_TURNS_PER_SESSION; null
    }

    private suspend fun handleCommands(initialReply: String): String {
        var reply = initialReply
        commandLoop@ for (i in 0 until COMMAND_LOOP_MAX) {
            val clean = reply.trim().removeSurrounding("```").trim().lowercase()
            val fsResult = when {
                clean == "ls"            -> OutputDirectoryManager.getRootFolder(context).listFiles()?.joinToString("\n") { it.name } ?: "Empty"
                clean == "ls -r"         -> OutputDirectoryManager.listRecursive(context)
                clean.startsWith("cat ") -> OutputDirectoryManager.readFile(context, reply.trim().substring(4).trim())
                clean.startsWith("count ") -> OutputDirectoryManager.countLines(context, reply.trim().substring(6).trim())
                else                     -> break@commandLoop
            }
            Log.d(TAG, "📂 handleCommands: Processing FS command: $clean")
            reply = try { webView.send("SYSTEM FILESYSTEM RESPONSE:\n$fsResult\n\nNow complete your task. Output only the requested content.") }
            catch (e: Exception) { Log.e(TAG, "❌ handleCommands: Command follow-up failed: ${e.message}"); break@commandLoop }
        }
        return reply
    }

    private fun isErrorReply(reply: String) = ERROR_SIGNATURES.any { reply.lowercase().trim().contains(it) }

    private fun buildPriorPartsText(solved: List<SolvedSubTask>): String {
        val parts = solved.filter { it.success && it.content.isNotBlank() }.takeLast(3)
        if (parts.isEmpty()) return "None yet."
        return parts.joinToString("\n\n") { s ->
            val context = if (s.abstract.isNotBlank()) s.abstract else s.content.lines().take(8).joinToString("\n")
            "▸ Part ${s.subtask.index}: ${s.subtask.title}\n$context"
        }
    }

    private fun loadPartials(file: File): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val o = JSONObject(file.readText())
            mutableMapOf<String, String>().also { m -> val k = o.keys(); while (k.hasNext()) { val key = k.next(); m[key] = o.getString(key) } }
        } catch (e: Exception) { mutableMapOf() }
    }

    private fun savePartials(file: File, map: Map<String, String>) {
        try {
            val tmp = File(file.parent, "${file.name}.tmp")
            tmp.writeText(JSONObject(map).toString(2))
            if (!tmp.renameTo(file)) { file.writeText(JSONObject(map).toString(2)); tmp.delete() }
        } catch (_: Exception) { }
    }

    fun cancel() { cancelled = true }
    private suspend fun notifyProgress(cb: (ProgressEvent) -> Unit, e: ProgressEvent) = withContext(Dispatchers.Main) { cb(e) }

    data class TaskResult(val task: TopLevelTask, val success: Boolean, val message: String, val finalFilePaths: List<String>) {
        val finalFilePath: String? get() = finalFilePaths.firstOrNull()
    }

    data class RunSummary(val total: Int, val succeeded: Int, val failedThisRun: Int, val results: List<TaskResult>, val checkpointPath: String?, val finalFiles: List<String>) {
        val isResumable get() = checkpointPath != null
        fun toStatusLine(): String {
            val names = finalFiles.map { File(it).name }
            return when {
                succeeded == 0    -> "❌ All tasks failed"
                failedThisRun > 0 -> "⚠️ $succeeded/$total done — ${names.joinToString(", ")}"
                names.size == 1   -> "✅ Done — ${names.first()}"
                else              -> "✅ Done — ${names.joinToString(", ")}"
            }
        }
    }

    sealed class ProgressEvent {
        data class Resumed(val alreadyDone: Int, val total: Int, val remaining: Int) : ProgressEvent()
        data class TaskStarted(val taskIndex: Int, val total: Int, val doneSoFar: Int, val filename: String) : ProgressEvent()
        data class TaskFinished(val taskIndex: Int, val total: Int, val doneSoFar: Int, val result: TaskResult) : ProgressEvent()
        data class StageChanged(val taskIndex: Int, val filename: String, val stage: TopLevelTask.Stage) : ProgressEvent()
        data class Planned(val taskIndex: Int, val filename: String, val subtaskCount: Int, val dedupeValue: String, val manifest: PlanManifest = PlanManifest.DEFAULT) : ProgressEvent()
        data class SubTaskStarted(val taskIndex: Int, val subtaskIndex: Int, val totalSubtasks: Int, val title: String) : ProgressEvent()
        data class SubTaskDone(val taskIndex: Int, val subtaskIndex: Int, val total: Int, val success: Boolean) : ProgressEvent()
        data class AnalysisStarted(val taskIndex: Int, val filename: String) : ProgressEvent()
        data class OutputAnalyzed(val taskIndex: Int, val filename: String, val report: OutputAnalyzer.AnalysisReport) : ProgressEvent()
    }
}
