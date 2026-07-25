package com.mit.attendance.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

private const val TAG = "AgentOrchestrator"

// ─────────────────────────────────────────
//  AGENT ORCHESTRATOR  v2
// ─────────────────────────────────────────
class AgentOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val session   = QwenAgentSession(context)
    private val metaAgent = MetaAgent(context, session, storageBaseDir = context.filesDir.absolutePath)

    private var workerAgent: WorkerAgent? = null
    private var workerJob:   Job?         = null

    enum class State { IDLE, META, WORKING }
    var state: State = State.IDLE
        private set

    fun start(
        onMetaReady: (greeting: String) -> Unit,
        onMetaError: (error: String) -> Unit
    ) {
        Log.d(TAG, "🚀 start: Initializing orchestrator...")
        scope.launch {
            try {
                session.init()
                Log.d(TAG, "⏱️ start: Qwen session ready, starting Meta session...")
                state = State.META
                metaAgent.startSession(
                    onReady = { greeting ->
                        Log.d(TAG, "✅ start: Meta session ready")
                        onMetaReady(greeting)
                    },
                    onError = { err ->
                        Log.e(TAG, "❌ start: Meta session error: $err")
                        state = State.IDLE
                        onMetaError(err)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ start: Qwen init error: ${e.message}")
                state = State.IDLE
                onMetaError(e.message ?: "Failed to connect to Qwen")
            }
        }
    }

    fun userMessage(message: String, onResult: (MetaAgent.ChatResult) -> Unit) {
        Log.d(TAG, "✉️ userMessage: Sending message to MetaAgent")
        scope.launch {
            val startTime = System.currentTimeMillis()
            val result = metaAgent.chat(message)
            Log.d(TAG, "✅ userMessage: MetaAgent replied in ${System.currentTimeMillis() - startTime}ms")
            onResult(result)
        }
    }

    fun startWorker(
        config: AgentConfig,
        onProgress: (event: WorkerAgent.ProgressEvent) -> Unit,
        onComplete: (WorkerAgent.RunSummary) -> Unit
    ) {
        if (state == State.WORKING) {
            Log.w(TAG, "⚠️ startWorker: Already working, ignore request")
            return
        }

        Log.d(TAG, "🚀 startWorker: Launching WorkerAgent for '${config.taskDescription}'")
        val agent = WorkerAgent(context, session)
        workerAgent = agent
        state       = State.WORKING

        workerJob = scope.launch {
            val startTime = System.currentTimeMillis()
            agent.run(
                config     = config,
                onProgress = onProgress,
                onComplete = { summary ->
                    Log.d(TAG, "🏁 startWorker: Worker finished in ${System.currentTimeMillis() - startTime}ms. Success: ${summary.succeeded}/${summary.total}")
                    state = State.IDLE
                    workerJob = null
                    onComplete(summary)
                }
            )
        }
    }

    fun cancelWorker() {
        Log.d(TAG, "⏹️ cancelWorker: Requesting cancellation")
        workerAgent?.cancel()
        workerJob?.cancel()
        workerJob   = null
        workerAgent = null
        state       = State.IDLE
    }

    fun resetMeta(
        onReady: (greeting: String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (state == State.WORKING) {
            Log.e(TAG, "❌ resetMeta: Cannot reset while worker is running")
            onError("Cannot reset Meta-Agent while a worker is running.")
            return
        }
        Log.d(TAG, "🔄 resetMeta: Resetting session...")
        metaAgent.reset()
        state = State.META
        scope.launch {
            metaAgent.startSession(
                onReady = { greeting -> 
                    Log.d(TAG, "✅ resetMeta: Session reset success")
                    onReady(greeting) 
                },
                onError = { err ->
                    Log.e(TAG, "❌ resetMeta: Session reset failed: $err")
                    state = State.IDLE
                    onError(err)
                }
            )
        }
    }

    fun setPaused(paused: Boolean) {
        session.isPaused = paused
    }

    fun isPaused(): Boolean = session.isPaused

    fun destroy() {
        Log.d(TAG, "🗑️ destroy: Cleaning up orchestrator")
        cancelWorker()
        session.destroy()
        state = State.IDLE
    }
}
