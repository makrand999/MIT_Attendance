package com.mit.attendance.agent

import android.content.Context
import android.util.Log
import com.mit.attendance.BuildConfig
import com.mit.attendance.QwenWebSocketClient
import com.mit.attendance.data.prefs.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val TAG = "QwenAgentSession"

class QwenAgentSession(
    private val context: Context,
    private val serverUrl: String = BuildConfig.QWEN_WS_URL
) {
    @Volatile
    private var projectName: String = DEFAULT_PROJECT

    @Volatile
    private var connectedProjectName: String? = null

    @Volatile
    private var resumeSessionId: String? = null

    @Volatile
    private var connectedResumeSessionId: String? = null

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    var isReady = false
        private set

    @Volatile
    var isPaused = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val sendMutex = Mutex()
    private val stateLock = Any()
    private val prefs = UserPreferences(context.applicationContext)
    private var eventListener: ((JSONObject) -> Unit)? = null

    private var readySignal = CompletableDeferred<Unit>()
    private var client: QwenWebSocketClient? = null
    private var pending: PendingResponse? = null

    suspend fun init() {
        ensureConnected(forceReconnect = true)
    }

    suspend fun awaitReady() {
        readySignal.await()
    }

    suspend fun refresh() {
        ensureConnected(forceReconnect = true)
    }

    suspend fun clearSession() {
        refresh()
    }

    fun getCurrentSessionId(): String? = currentSessionId ?: resumeSessionId

    fun setProject(projectName: String) {
        this.projectName = projectName.trim().ifBlank { DEFAULT_PROJECT }
    }

    fun setResumeSessionId(sessionId: String?) {
        resumeSessionId = sessionId?.trim()?.takeIf { it.isNotBlank() }
    }

    fun clearResumeSession() {
        resumeSessionId = null
    }

    fun resetTrackedSession() {
        currentSessionId = null
    }

    fun setEventListener(listener: ((JSONObject) -> Unit)?) {
        eventListener = listener
    }

    suspend fun send(message: String): String {
        while (isPaused) {
            delay(PAUSE_POLL_MS)
        }

        ensureConnected()

        return sendMutex.withLock {
            val socket = client ?: error("Qwen socket is not connected")
            val response = PendingResponse()
            synchronized(stateLock) {
                pending = response
            }

            try {
                socket.sendInput(message)
                withTimeout(RESPONSE_TIMEOUT_MS) {
                    response.result.await()
                }.trim()
            } finally {
                synchronized(stateLock) {
                    if (pending === response) pending = null
                }
                response.idleJob?.cancel()
            }
        }
    }

    fun destroy() {
        scope.launch {
            disconnectInternal()
        }
        scope.cancel()
    }

    private suspend fun ensureConnected(forceReconnect: Boolean = false) {
        connectMutex.withLock {
            val projectChanged = connectedProjectName != projectName
            val resumeChanged = connectedResumeSessionId != resumeSessionId
            if (!forceReconnect && !projectChanged && !resumeChanged && isReady && client?.isOpen == true) return

            if (forceReconnect || projectChanged || resumeChanged || client?.isOpen != true) {
                disconnectInternal()
                readySignal = CompletableDeferred()
                val userScopedUri = buildUserScopedUri()

                val nextClient = QwenWebSocketClient(
                    serverUri = userScopedUri,
                    onMessageReceived = ::handleMessage,
                    onOpenStarted = ::handleOpen,
                    onConnectionClosed = { handleClosed("Socket closed") },
                    onErrorOccurred = ::handleError
                )

                client = nextClient
                val connected = withContext(Dispatchers.IO) {
                    nextClient.connectBlocking(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                if (!connected) {
                    client = null
                    isReady = false
                    val error = IllegalStateException("Failed to connect to Qwen at $serverUrl")
                    if (!readySignal.isCompleted) readySignal.completeExceptionally(error)
                    throw error
                }
                connectedProjectName = projectName
                connectedResumeSessionId = resumeSessionId
            }
        }

        readySignal.await()
    }

    private suspend fun disconnectInternal() {
        val existing = client ?: return
        client = null
        isReady = false
        connectedProjectName = null
        connectedResumeSessionId = null
        runCatching {
            withContext(Dispatchers.IO) {
                if (existing.isOpen) {
                    existing.closeBlocking()
                } else {
                    existing.close()
                }
            }
        }
        failPending(IllegalStateException("Qwen connection reset"))
    }

    private fun handleOpen() {
        isReady = true
        if (!readySignal.isCompleted) readySignal.complete(Unit)
        Log.d(TAG, "Connected to Qwen at $serverUrl")
    }

    private fun handleClosed(reason: String) {
        isReady = false
        if (!readySignal.isCompleted) {
            readySignal.completeExceptionally(IllegalStateException(reason))
        }
        failPending(IllegalStateException(reason))
        Log.w(TAG, reason)
    }

    private fun handleError(error: Exception) {
        isReady = false
        if (!readySignal.isCompleted) readySignal.completeExceptionally(error)
        failPending(error)
        Log.e(TAG, "Qwen socket error: ${error.message}", error)
    }

    private fun handleMessage(raw: String) {
        val json = parseJson(raw)
        json?.optString("session_id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { currentSessionId = it }
        json?.let { eventListener?.invoke(it) }
        val status = json?.optString("status")?.lowercase()
        val type = json?.optString("type")?.lowercase()
        if (status in FAILURE_MARKERS || type in FAILURE_MARKERS) {
            failPending(IllegalStateException(extractText(raw).ifBlank { "Qwen server error." }))
            return
        }

        val text = extractText(raw)
        val isTerminal = isTerminalMessage(raw)
        val active = synchronized(stateLock) { pending } ?: run {
            Log.d(TAG, "Ignoring unsolicited socket message")
            return
        }

        if (text.isNotBlank()) {
            if (type == "final") {
                active.mergeFinalText(text)
            } else {
                active.buffer.append(text)
            }
            active.scheduleIdleCompletion(scope)
        }

        if (isTerminal) {
            active.complete()
        }
    }

    private fun failPending(error: Throwable) {
        val active = synchronized(stateLock) {
            pending.also { pending = null }
        } ?: return
        active.fail(error)
    }

    private fun extractText(raw: String): String {
        val json = parseJson(raw) ?: return raw
        val type = json.optString("type").lowercase()
        if (type !in TEXT_MESSAGE_TYPES) return ""
        return when {
            json.has("text") -> json.optString("text")
            json.has("message") -> json.optString("message")
            json.has("response") -> json.optString("response")
            json.has("content") -> json.optString("content")
            json.has("chunk") -> json.optString("chunk")
            else -> raw
        }
    }

    private fun isTerminalMessage(raw: String): Boolean {
        val json = parseJson(raw) ?: return false
        if (json.optBoolean("done") || json.optBoolean("isDone") || json.optBoolean("final")) {
            return true
        }

        val marker = listOf(
            json.optString("type"),
            json.optString("event"),
            json.optString("status")
        ).firstOrNull { it.isNotBlank() }?.lowercase()

        return marker in TERMINAL_MARKERS
    }

    private fun parseJson(raw: String): JSONObject? =
        runCatching { JSONObject(raw) }.getOrNull()

    private suspend fun buildUserScopedUri(): URI {
        val email = prefs.getEmail()
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No saved email found for Qwen authentication")

        val separator = if ('?' in serverUrl) "&" else "?"
        val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8.toString())
        val encodedProject = URLEncoder.encode(projectName, StandardCharsets.UTF_8.toString())
        val encodedResume = resumeSessionId
            ?.let { URLEncoder.encode(it, StandardCharsets.UTF_8.toString()) }
            ?.let { "&session_id=$it" }
            .orEmpty()
        val authQuery = AgentRequestAuth.buildQuery(email)
        return URI("$serverUrl${separator}email=$encodedEmail&project=$encodedProject$encodedResume&$authQuery")
    }

    private class PendingResponse {
        val buffer = StringBuilder()
        val result = CompletableDeferred<String>()
        var idleJob: Job? = null

        fun mergeFinalText(text: String) {
            val current = buffer.toString()
            when {
                current.isBlank() -> buffer.append(text)
                text == current -> Unit
                text.startsWith(current) -> {
                    buffer.setLength(0)
                    buffer.append(text)
                }
                current.endsWith(text) -> Unit
                else -> {
                    buffer.setLength(0)
                    buffer.append(text)
                }
            }
        }

        fun scheduleIdleCompletion(scope: CoroutineScope) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(STREAM_IDLE_MS)
                complete()
            }
        }

        fun complete() {
            idleJob?.cancel()
            if (!result.isCompleted) result.complete(buffer.toString())
        }

        fun fail(error: Throwable) {
            idleJob?.cancel()
            if (!result.isCompleted) result.completeExceptionally(error)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val RESPONSE_TIMEOUT_MS = 120_000L
        const val STREAM_IDLE_MS = 900L
        const val PAUSE_POLL_MS = 1_000L

        val TERMINAL_MARKERS = setOf(
            "done",
            "complete",
            "completed",
            "end",
            "finished",
            "final"
        )

        val FAILURE_MARKERS = setOf(
            "error",
            "busy",
            "auth_required"
        )

        val TEXT_MESSAGE_TYPES = setOf(
            "chunk",
            "final"
        )

        const val DEFAULT_PROJECT = "default"
    }
}
