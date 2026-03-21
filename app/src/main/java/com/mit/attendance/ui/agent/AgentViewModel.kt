package com.mit.attendance.ui.agent

import android.app.Application
import android.widget.FrameLayout
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mit.attendance.agent.*
import com.mit.attendance.service.ChatGptWebView
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { IDLE, LOADING, CONFIGURING, READY_TO_RUN, RUNNING, DONE }

    private val _phase    = MutableStateFlow(Phase.IDLE)
    private val _chat     = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _pipeline = MutableStateFlow<PipelineState?>(null)
    private val _summary  = MutableStateFlow<WorkerAgent.RunSummary?>(null)
    private val _error    = MutableStateFlow<String?>(null)
    private val _resume   = MutableStateFlow<ResumeInfo?>(null)
    private val _history  = MutableStateFlow<List<OutputDirectoryManager.TaskFolder>>(emptyList())
    private val _isNewTaskSession = MutableStateFlow(false)
    private val _isPaused = MutableStateFlow(false)
    
    // File Explorer State
    private val _explorerFiles = MutableStateFlow<List<File>>(emptyList())
    private val _selectedFileContent = MutableStateFlow<Pair<File, String>?>(null)
    private val _currentDirectory = MutableStateFlow<File?>(null)

    val phase   = _phase.asStateFlow()
    val chat    = _chat.asStateFlow()
    val pipeline= _pipeline.asStateFlow()
    val summary = _summary.asStateFlow()
    val error   = _error.asStateFlow()
    val resume  = _resume.asStateFlow()
    val history = _history.asStateFlow()
    val isNewTaskSession = _isNewTaskSession.asStateFlow()
    val isPaused = _isPaused.asStateFlow()
    
    val explorerFiles = _explorerFiles.asStateFlow()
    val selectedFileContent = _selectedFileContent.asStateFlow()
    val currentDirectory = _currentDirectory.asStateFlow()

    private var orchestrator: AgentOrchestrator? = null
    private var pendingConfig: AgentConfig? = null
    private var cachedGreeting: String? = null

    fun init(hiddenContainer: FrameLayout, loginManager: com.mit.attendance.auth.ChatGptLoginManager) {
        if (orchestrator != null) return
        orchestrator = AgentOrchestrator(getApplication(), hiddenContainer, viewModelScope)
        _phase.value = Phase.LOADING
        loadHistory()
        orchestrator!!.start(
            loginManager = loginManager,
            onMetaReady = { greeting ->
                cachedGreeting = greeting
                _phase.value = Phase.CONFIGURING
            },
            onMetaError = { err -> _error.value = err; _phase.value = Phase.IDLE }
        )
    }

    fun togglePause() {
        orchestrator?.let {
            val newState = !it.isPaused()
            it.setPaused(newState)
            _isPaused.value = newState
        }
    }

    fun loadHistory() {
        _history.value = OutputDirectoryManager.listTaskFolders(getApplication())
    }

    fun browseFiles(directory: File? = null) {
        val root = OutputDirectoryManager.getRootFolder(getApplication())
        val target = directory ?: root
        _currentDirectory.value = if (target == root) null else target
        _explorerFiles.value = target.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            browseFiles(file)
        } else {
            val content = try { file.readText() } catch (e: Exception) { "Error reading file: ${e.message}" }
            _selectedFileContent.value = file to content
        }
    }

    fun closeFile() {
        _selectedFileContent.value = null
    }

    fun navigateUp(): Boolean {
        if (_selectedFileContent.value != null) {
            _selectedFileContent.value = null
            return true
        }
        val current = _currentDirectory.value
        if (current != null) {
            val root = OutputDirectoryManager.getRootFolder(getApplication())
            val parent = current.parentFile
            if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
                browseFiles(if (parent == root) null else parent)
            } else {
                browseFiles(null)
            }
            return true
        }
        return false
    }

    fun startNewTask() {
        _isNewTaskSession.value = true
        _resume.value = null
        cachedGreeting?.let { greeting ->
            if (_chat.value.isEmpty()) {
                addMessage(ChatMessage("assistant", greeting))
            }
        }
    }

    fun selectFolderToResume(folder: OutputDirectoryManager.TaskFolder) {
        checkForResumableRun(folder.path)
        _isNewTaskSession.value = false
        // Clear any existing chat if we switch to a resume task
        _chat.value = emptyList()
    }

    fun checkForResumableRun(outputDir: String) {
        val cp = CheckpointManager(outputDir)
        if (!cp.exists()) {
            _error.value = "No resumable checkpoint found in this folder."
            return
        }
        val state  = cp.load() ?: run {
            _error.value = "Failed to load checkpoint file."
            return
        }
        val config = runCatching { AgentConfig.fromJson(state.configJson) }.getOrNull() ?: run {
            _error.value = "Invalid configuration in checkpoint."
            return
        }
        _resume.value = ResumeInfo(config, state.completedCount, config.totalTasks, outputDir)
    }

    fun resumeRun() {
        val resumeInfo = _resume.value ?: return
        pendingConfig = resumeInfo.config
        val outputDir = resumeInfo.outputDir
        _resume.value = null
        
        _phase.value = Phase.RUNNING
        _pipeline.value = PipelineState(totalTasks = pendingConfig!!.totalTasks, outputDir = outputDir)

        orchestrator?.startWorker(
            config     = pendingConfig!!,
            onProgress = { event -> handleEvent(event) },
            onComplete = { summary ->
                _summary.value = summary
                _phase.value   = Phase.DONE
                if (summary.isResumable) {
                    _resume.value = ResumeInfo(pendingConfig!!, summary.succeeded, summary.total, outputDir)
                }
                loadHistory()
            }
        )
    }

    fun discardCheckpoint(outputDir: String) {
        CheckpointManager(outputDir).clear()
        _resume.value = null
        loadHistory()
    }

    fun sendMessage(text: String) {
        if (_phase.value != Phase.CONFIGURING) return
        addMessage(ChatMessage("user", text))
        orchestrator?.userMessage(text) { result ->
            val clean = result.reply.replace(Regex("```agent_config[\\s\\S]*?```"), "").trim()
            addMessage(ChatMessage("assistant", clean))
            if (result.configReady && result.config != null) {
                pendingConfig = result.config
                _phase.value = Phase.READY_TO_RUN
            }
        }
    }

    fun confirmAndRun() {
        val config = pendingConfig ?: return

        // Clear chat history before running the agent
        _chat.value = emptyList()

        val outputDir = OutputDirectoryManager
            .createTaskFolder(getApplication(), config.taskDescription)
            ?: run { _error.value = "Could not create output folder"; return }

        val runConfig = config.copy(outputDirectory = outputDir)
        pendingConfig = runConfig

        _phase.value    = Phase.RUNNING
        _pipeline.value = PipelineState(totalTasks = runConfig.totalTasks, outputDir = outputDir)

        orchestrator?.startWorker(
            config     = runConfig,
            onProgress = { event -> handleEvent(event) },
            onComplete = { summary ->
                _summary.value = summary
                _phase.value   = Phase.DONE
                if (summary.isResumable) {
                    _resume.value = ResumeInfo(runConfig, summary.succeeded, summary.total, outputDir)
                }
                loadHistory()
            }
        )
    }

    private fun handleEvent(e: WorkerAgent.ProgressEvent) {
        val cur = _pipeline.value ?: return
        _pipeline.value = when (e) {
            is WorkerAgent.ProgressEvent.Resumed       -> cur.copy(tasksDone = e.alreadyDone, currentStage = "▶️ Resuming — ${e.alreadyDone}/${e.total} done")
            is WorkerAgent.ProgressEvent.TaskStarted   -> cur.copy(currentTask = e.taskIndex, currentFilename = e.filename, tasksDone = e.doneSoFar, currentStage = "Starting…", subtasksDone = 0, subtasksTotal = 0, currentDedupeValue = null)
            is WorkerAgent.ProgressEvent.StageChanged  -> cur.copy(currentStage = stageLabel(e.stage))
            is WorkerAgent.ProgressEvent.Planned       -> cur.copy(subtasksTotal = e.subtaskCount, subtasksDone = 0, currentDedupeValue = e.dedupeValue.ifBlank { null }, currentStage = "✍️ ${e.subtaskCount} subtasks planned")
            is WorkerAgent.ProgressEvent.SubTaskStarted-> cur.copy(currentStage = "✍️ ${e.subtaskIndex}/${e.totalSubtasks}: ${e.title}")
            is WorkerAgent.ProgressEvent.SubTaskDone   -> cur.copy(subtasksDone = cur.subtasksDone + 1)
            is WorkerAgent.ProgressEvent.AnalysisStarted -> cur.copy(currentStage = "🔍 Analyzing ${e.filename}…")
            is WorkerAgent.ProgressEvent.OutputAnalyzed  -> cur.copy(currentStage = "🔍 Analysis done")
            is WorkerAgent.ProgressEvent.TaskFinished  -> cur.copy(tasksDone = e.doneSoFar, currentStage = if (e.result.success) "✅ ${e.result.task.filename}" else "❌ Failed")
        }
    }

    private fun stageLabel(s: TopLevelTask.Stage) = when (s) {
        TopLevelTask.Stage.PLAN    -> "📋 Planning subtasks…"
        TopLevelTask.Stage.SOLVE   -> "✍️ Solving subtasks…"
        TopLevelTask.Stage.COMBINE -> "🔗 Combining…"
        TopLevelTask.Stage.DONE    -> "✅ Done"
        TopLevelTask.Stage.FAILED  -> "❌ Failed"
    }

    fun clearError() { _error.value = null }

    fun cancel()  { orchestrator?.cancelWorker(); _phase.value = Phase.DONE; loadHistory() }
    fun restart() {
        pendingConfig = null; _summary.value = null; _error.value = null
        _pipeline.value = null; _chat.value = emptyList(); _resume.value = null
        _phase.value = Phase.LOADING
        orchestrator?.resetMeta(
            onReady = { greeting ->
                cachedGreeting = greeting
                addMessage(ChatMessage("assistant", greeting))
                _phase.value = Phase.CONFIGURING
                _isNewTaskSession.value = true
            },
            onError = { err ->
                _error.value = err
                _phase.value = Phase.IDLE
            }
        )
        loadHistory()
    }

    override fun onCleared() { orchestrator?.destroy(); super.onCleared() }
    private fun addMessage(m: ChatMessage) { _chat.value = _chat.value + m }

    data class ChatMessage(val role: String, val text: String)

    data class PipelineState(
        val totalTasks: Int,
        val tasksDone: Int = 0,
        val currentTask: Int = 0,
        val currentFilename: String = "",
        val currentStage: String = "",
        val currentDedupeValue: String? = null,
        val subtasksTotal: Int = 0,
        val subtasksDone: Int = 0,
        val outputDir: String = ""
    ) {
        val taskPercent: Int    get() = if (totalTasks == 0) 0 else (tasksDone * 100) / totalTasks
        val subtaskPercent: Int get() = if (subtasksTotal == 0) 0 else (subtasksDone * 100) / subtasksTotal
    }

    data class ResumeInfo(val config: AgentConfig, val alreadyDone: Int, val total: Int, val outputDir: String) {
        val label: String get() = "Resume: $alreadyDone/$total done (${total - alreadyDone} remaining)"
    }
}
