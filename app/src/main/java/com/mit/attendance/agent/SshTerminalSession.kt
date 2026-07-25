package com.mit.attendance.agent

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mit.attendance.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties

private const val TAG = "SshTerminalSession"

class SshTerminalSession(
    context: Context
) {
    @Volatile
    private var projectName: String = DEFAULT_PROJECT

    @Volatile
    private var userId: String? = null

    @Volatile
    var isReady = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var shellInput: OutputStream? = null
    private var readerJob: Job? = null
    private var outputListener: ((String) -> Unit)? = null
    private var suppressBootstrapOutput = true
    private val bootstrapOutputBuffer = StringBuilder()
    @Volatile
    private var terminalColumns: Int = DEFAULT_COLUMNS
    @Volatile
    private var terminalRows: Int = DEFAULT_ROWS
    @Volatile
    private var terminalWidthPx: Int = 0
    @Volatile
    private var terminalHeightPx: Int = 0

    init {
        @Suppress("UNUSED_PARAMETER")
        val ignored = context.applicationContext
    }

    suspend fun init() {
        withContext(Dispatchers.IO) {
            reconnect()
        }
    }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            reconnect()
        }
    }

    fun setProject(projectName: String) {
        this.projectName = projectName.trim().ifBlank { DEFAULT_PROJECT }
    }

    fun setUserEmail(email: String) {
        userId = sha256(email.trim().lowercase()).take(24)
    }

    fun setOutputListener(listener: ((String) -> Unit)?) {
        outputListener = listener
    }

    suspend fun sendCommand(command: String) {
        sendRawInput(if (command.endsWith("\n")) command else "$command\n")
    }

    suspend fun sendRawInput(data: String) {
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                ensureConnectedLocked()
                val input = shellInput ?: error("SSH shell is not connected")
                input.write(data.toByteArray())
                input.flush()
            }
        }
    }

    suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                terminalColumns = columns.coerceAtLeast(MIN_COLUMNS)
                terminalRows = rows.coerceAtLeast(MIN_ROWS)
                terminalWidthPx = widthPx.coerceAtLeast(0)
                terminalHeightPx = heightPx.coerceAtLeast(0)
                channel?.setPtySize(terminalColumns, terminalRows, terminalWidthPx, terminalHeightPx)
            }
        }
    }

    fun destroy() {
        scope.launch {
            disconnectLocked()
        }
        scope.cancel()
    }

    private suspend fun reconnect() {
        connectMutex.withLock {
            disconnectLocked()
            ensureConnectedLocked()
        }
    }

    private fun ensureConnectedLocked() {
        val resolvedUserId = userId ?: error("Missing user email for SSH workspace resolution")
        if (channel?.isConnected == true && session?.isConnected == true) return

        val sshSession = JSch().getSession(sshUsernameForUserId(resolvedUserId), BuildConfig.SSH_HOST, BuildConfig.SSH_PORT)
        sshSession.setPassword(BuildConfig.SSH_PASSWORD)
        sshSession.timeout = CONNECT_TIMEOUT_MS
        sshSession.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password")
        })
        sshSession.connect(CONNECT_TIMEOUT_MS)

        val shellChannel = sshSession.openChannel("shell") as ChannelShell
        shellChannel.setPtyType(
            "xterm-256color",
            terminalColumns,
            terminalRows,
            terminalWidthPx,
            terminalHeightPx
        )
        shellChannel.setEnv("TERM", "xterm-256color")
        val output = shellChannel.outputStream
        val input = shellChannel.inputStream
        shellChannel.connect(CONNECT_TIMEOUT_MS)

        session = sshSession
        channel = shellChannel
        shellInput = output
        isReady = true
        suppressBootstrapOutput = true
        bootstrapOutputBuffer.clear()
        startReader(input)
        writeBootstrapCommands(resolvedUserId)
        Log.d(TAG, "SSH shell connected to ${BuildConfig.SSH_HOST}")
    }

    private fun writeBootstrapCommands(userId: String) {
        val input = shellInput ?: return
        val userBaseDir = userBasePath(userId)
        val runtimeDir = userRuntimePath(userId)
        val workspaceDir = projectWorkspacePath(userId, projectName)
        val xdgConfigDir = pathJoin(userBaseDir, ".config")
        val bootstrap = buildString {
            append("export TERM=xterm-256color\n")
            append("export HOME=")
            append(escapeShellArg(userBaseDir))
            append("\n")
            append("export XDG_CONFIG_HOME=")
            append(escapeShellArg(xdgConfigDir))
            append("\n")
            append("export QWEN_RUNTIME_DIR=")
            append(escapeShellArg(runtimeDir))
            append("\n")
            append("mkdir -p ")
            append(escapeShellArg(pathJoin(userBaseDir, ".qwen")))
            append(" ")
            append(escapeShellArg(pathJoin(userBaseDir, ".codex")))
            append(" ")
            append(escapeShellArg(xdgConfigDir))
            append(" ")
            append(escapeShellArg(runtimeDir))
            append(" ")
            append(escapeShellArg(workspaceDir))
            append("\n")
            append("export PS1='")
            append(projectName.replace("'", ""))
            append(" $ '\n")
            append("unset PROMPT_COMMAND\n")
            append("cd ")
            append(escapeShellArg(workspaceDir))
            append("\n")
            append("printf '\\033[3J\\033[H\\033[2J'\n")
        }
        input.write(bootstrap.toByteArray())
        input.flush()
    }

    private fun startReader(input: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    deliverOutput(String(buffer, 0, count))
                }
            } catch (error: Exception) {
                deliverOutput("\n[SSH read error: ${error.message}]\n")
                Log.e(TAG, "SSH reader failed: ${error.message}", error)
            } finally {
                isReady = false
            }
        }
    }

    private fun deliverOutput(chunk: String) {
        if (!suppressBootstrapOutput) {
            outputListener?.invoke(chunk)
            return
        }

        bootstrapOutputBuffer.append(chunk)
        val fullOutput = bootstrapOutputBuffer.toString()
        val clearIndex = fullOutput.indexOf(BOOTSTRAP_CLEAR_SEQUENCE)
        if (clearIndex < 0) {
            if (bootstrapOutputBuffer.length > MAX_BOOTSTRAP_BUFFER) {
                bootstrapOutputBuffer.delete(
                    0,
                    bootstrapOutputBuffer.length - BOOTSTRAP_CLEAR_SEQUENCE.length
                )
            }
            return
        }

        suppressBootstrapOutput = false
        val remaining = fullOutput.substring(clearIndex + BOOTSTRAP_CLEAR_SEQUENCE.length)
        bootstrapOutputBuffer.clear()
        if (remaining.isNotEmpty()) {
            outputListener?.invoke(remaining)
        }
    }

    private fun disconnectLocked() {
        readerJob?.cancel()
        readerJob = null
        shellInput = null
        suppressBootstrapOutput = true
        bootstrapOutputBuffer.clear()

        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }

        channel = null
        session = null
        isReady = false
    }

    private fun userBasePath(userId: String): String =
        "/srv/qwen-users/$userId"

    private fun sshUsernameForUserId(userId: String): String =
        "agent_$userId"

    private fun userRuntimePath(userId: String): String =
        pathJoin(userBasePath(userId), ".qwen-runtime")

    private fun projectWorkspacePath(userId: String, project: String): String =
        pathJoin(userBasePath(userId), "workspace", project)

    private fun pathJoin(vararg parts: String): String =
        buildString {
            parts.forEachIndexed { index, part ->
                val normalized = if (index == 0) part.trimEnd('/') else part.trim('/')
                if (normalized.isEmpty()) return@forEachIndexed
                if (isNotEmpty() && !endsWith('/')) append('/')
                append(normalized)
            }
        }

    private fun escapeShellArg(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(bytes.size * 2) {
            for (byte in bytes) append("%02x".format(byte))
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_PROJECT = "default"
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_ROWS = 32
        const val MIN_COLUMNS = 40
        const val MIN_ROWS = 12
        const val BOOTSTRAP_CLEAR_SEQUENCE = "\u001B[3J\u001B[H\u001B[2J"
        const val MAX_BOOTSTRAP_BUFFER = 32_768
    }
}
