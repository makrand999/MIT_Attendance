package com.mit.attendance.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.mit.attendance.R
import com.mit.attendance.agent.SshTerminalSession
import com.mit.attendance.ui.agent.AgentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AgentSshService : Service() {

    interface Listener {
        fun onTerminalCleared()
        fun onTerminalOutput(chunk: String)
        fun onServiceError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AgentSshService = this@AgentSshService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionUpdateMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var session: SshTerminalSession

    @Volatile
    private var listener: Listener? = null

    private val terminalSnapshot = StringBuilder()
    private var currentEmail: String? = null
    private var currentProject: String? = null
    private var hasInitialized = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        session = SshTerminalSession(applicationContext).apply {
            setOutputListener(::appendTerminalOutput)
        }
        startForegroundCompat(buildNotification())
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        session.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getSnapshot(): String = terminalSnapshot.toString()

    fun connect(email: String, project: String) {
        serviceScope.launch {
            if (hasInitialized && currentEmail == email && currentProject == project && session.isReady) {
                return@launch
            }
            runSessionUpdate(email, project, forceReconnect = hasInitialized)
        }
    }

    fun restart(email: String, project: String) {
        serviceScope.launch {
            runSessionUpdate(email, project, forceReconnect = true)
        }
    }

    fun sendTerminalInput(data: String) {
        if (data.isEmpty()) return
        serviceScope.launch {
            runCatching { session.sendRawInput(data) }
                .onFailure { notifyError(it.message ?: "Terminal input failed.") }
        }
    }

    fun resizeTerminal(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        serviceScope.launch {
            runCatching { session.resize(columns, rows, widthPx, heightPx) }
        }
    }

    private suspend fun runSessionUpdate(email: String, project: String, forceReconnect: Boolean) {
        sessionUpdateMutex.withLock {
            runCatching {
                currentEmail = email
                currentProject = project
                resetTerminal()
                session.setUserEmail(email)
                session.setProject(project)
                if (forceReconnect) {
                    session.refresh()
                } else {
                    session.init()
                    hasInitialized = true
                }
                updateNotification(project)
            }.onFailure {
                notifyError("Failed to connect: ${it.message}")
            }
        }
    }

    private fun appendTerminalOutput(chunk: String) {
        if (chunk.isEmpty()) return
        terminalSnapshot.append(chunk)
        dispatchToListener { it.onTerminalOutput(chunk) }
    }

    private fun resetTerminal() {
        terminalSnapshot.setLength(0)
        dispatchToListener { it.onTerminalCleared() }
    }

    private fun notifyError(message: String) {
        dispatchToListener { it.onServiceError(message) }
    }

    private fun dispatchToListener(block: (Listener) -> Unit) {
        mainHandler.post {
            listener?.let(block)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(project: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(project))
    }

    private fun buildNotification(project: String? = currentProject): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AgentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = project?.let { "SSH session active for $it" } ?: "SSH session active"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon)
            .setContentTitle("Agent Session")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "agent_ssh_session"
        private const val CHANNEL_NAME = "Agent Session"
        private const val NOTIFICATION_ID = 4101

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the agent SSH terminal alive in the background."
            }
            manager.createNotificationChannel(channel)
        }
    }
}
