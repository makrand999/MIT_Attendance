package com.mit.attendance.ui.agent

import android.app.AlertDialog
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityAgentBinding
import com.mit.attendance.databinding.ItemRemoteFileBinding
import com.mit.attendance.service.AgentSshService
import com.mit.attendance.storage.OutputDirectoryManager
import com.mit.attendance.ui.subjects.NotesActivity
import com.termux.terminal.SshTermuxSession
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class AgentActivity : AppCompatActivity(), TerminalViewClient, TerminalSessionClient, AgentSshService.Listener {

    private lateinit var binding: ActivityAgentBinding
    private val viewModel: AgentViewModel by viewModels()
    private lateinit var fileAdapter: RemoteFileAdapter
    private lateinit var terminalSession: SshTermuxSession
    private lateinit var prefs: UserPreferences
    private var terminalScale = 1f
    private var swipeTypingEnabled = false
    private var shiftEnabled = false
    private var agentService: AgentSshService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AgentSshService.LocalBinder ?: return
            agentService = binder.getService()
            isServiceBound = true
            syncTerminalFromService()
            agentService?.setListener(this@AgentActivity)
            ensureServiceConnectedForSelectedProject()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService?.setListener(null)
            agentService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = UserPreferences(applicationContext)

        setupUi()
        observeViewModel()
        viewModel.init()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutFileManager.isVisible) {
                    setWorkspaceVisible(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        binding.wvTerminal.setTerminalCursorBlinkerState(true, true)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AgentSshService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (isServiceBound) {
            agentService?.setListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        agentService = null
        super.onStop()
    }

    override fun onPause() {
        binding.wvTerminal.setTerminalCursorBlinkerState(false, false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::terminalSession.isInitialized) {
            terminalSession.markClosed()
        }
        super.onDestroy()
    }

    private fun setupUi() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""
        binding.toolbar.title = ""
        binding.toolbar.setNavigationOnClickListener { finish() }
        pinBackgroundHeight()
        binding.btnResumeChat.isVisible = false

        binding.wvTerminal.setTerminalViewClient(this)
        binding.wvTerminal.setTextSize(14)
        binding.wvTerminal.setTerminalCursorBlinkerRate(600)
        binding.wvTerminal.isFocusable = true
        binding.wvTerminal.isFocusableInTouchMode = true
        binding.wvTerminal.setOnTouchListener { _, _ ->
            binding.layoutFileManager.isVisible
        }
        binding.wvTerminal.requestFocus()
        resetTerminalSession(replaySnapshot = true)

        fileAdapter = RemoteFileAdapter { remoteFile ->
            viewModel.handleFileClick(remoteFile)
        }
        binding.rvRemoteFiles.layoutManager = LinearLayoutManager(this)
        binding.rvRemoteFiles.adapter = fileAdapter

        binding.btnRestart.setOnClickListener {
            restartAgentSession()
        }
        binding.btnTools.setOnClickListener {
            showAgentToolPicker()
        }
        binding.btnFilesToggle.setOnClickListener {
            if (binding.layoutFileManager.isVisible) {
                openAgentFileExplorer()
            } else {
                setWorkspaceVisible(true)
                viewModel.loadProjects()
            }
        }
        binding.btnCloseFiles.setOnClickListener {
            setWorkspaceVisible(false)
        }
        binding.viewWorkspaceScrim.setOnClickListener {
            setWorkspaceVisible(false)
        }
        binding.btnProjectPicker.setOnClickListener {
            showProjectPicker()
        }
        binding.btnProjectCreate.setOnClickListener {
            showCreateProjectDialog()
        }
        binding.btnProjectUp.setOnClickListener {
            viewModel.navigateUp()
        }

        binding.btnTermMark.setOnClickListener {
            toggleSelectionMode()
        }
        binding.btnTermCopy.setOnClickListener {
            copyTextToClipboard(binding.wvTerminal.getSelectedTextOrTranscript())
        }
        binding.btnTermSelectAll.setOnClickListener {
            copyTextToClipboard(binding.wvTerminal.getTranscriptText())
        }
        binding.btnTermInputMode.setOnClickListener {
            toggleSwipeTypingMode()
        }
        binding.btnTermShift.setOnClickListener {
            toggleShiftMode()
        }
        binding.etSwipeInput.setOnEditorActionListener { _, actionId, event ->
            val isImeSend = actionId == EditorInfo.IME_ACTION_SEND
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isImeSend || isEnterKey) {
                submitSwipeInput()
                true
            } else {
                false
            }
        }
        binding.btnTermCtrlC.setOnClickListener {
            sendTerminalShortcut("\u0003")
        }
        binding.btnTermEsc.setOnClickListener {
            if (binding.wvTerminal.isSelectingText()) {
                binding.wvTerminal.stopTextSelectionMode()
            } else {
                sendTerminalShortcut("\u001B")
            }
        }
        binding.btnTermTab.setOnClickListener {
            if (shiftEnabled) {
                sendTerminalShortcut("\u001B[Z")
            } else {
                sendTerminalShortcut("\t")
            }
        }
        binding.btnTermUp.setOnClickListener {
            sendTerminalShortcut("\u001B[A")
        }
        binding.btnTermDown.setOnClickListener {
            sendTerminalShortcut("\u001B[B")
        }
        binding.btnTermLeft.setOnClickListener {
            sendTerminalShortcut("\u001B[D")
        }
        binding.btnTermRight.setOnClickListener {
            sendTerminalShortcut("\u001B[C")
        }
        binding.btnTermEnter.setOnClickListener {
            sendTerminalShortcut("\r")
        }

        renderSelectionMode(binding.wvTerminal.isSelectingText())
        renderInputMode()
        renderShiftMode()

        lifecycleScope.launch {
            swipeTypingEnabled = prefs.isTerminalSwipeTypingEnabled()
            renderInputMode()
            restartInputTargets(showKeyboard = false)
        }

        lifecycleScope.launch {
            seedAgentToolFilesIfMissing()
        }
    }

    private fun pinBackgroundHeight() {
        val screenHeight = resources.displayMetrics.heightPixels
        binding.ivBackground.layoutParams = binding.ivBackground.layoutParams.apply {
            height = screenHeight
        }
        binding.ivBackground.requestLayout()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.phase.collect { phase ->
                        binding.pbLoading.isVisible = phase == AgentViewModel.Phase.LOADING
                    }
                }
                launch {
                    viewModel.remoteFiles.collect { files ->
                        fileAdapter.submitList(files)
                        binding.tvExplorerEmpty.isVisible = files.isEmpty()
                    }
                }
                launch {
                    viewModel.projects.collect { projects ->
                        renderProjectHeader(projects, viewModel.selectedProject.value)
                    }
                }
                launch {
                    viewModel.selectedProject.collect { project ->
                        binding.tvCurrentProject.text = project ?: "No project"
                        renderProjectHeader(viewModel.projects.value, project)
                        ensureServiceConnectedForSelectedProject()
                    }
                }
                launch {
                    viewModel.currentPath.collect { path ->
                        binding.tvCurrentPath.text = if (path.isBlank()) "/" else "/$path"
                        binding.btnProjectUp.isEnabled = path.isNotBlank()
                        binding.btnProjectUp.alpha = if (path.isNotBlank()) 1f else 0.35f
                    }
                }
                launch {
                    viewModel.error.collect { err ->
                        if (err != null) {
                            Toast.makeText(this@AgentActivity, err, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }
                }
            }
        }
    }

    private fun showProjectPicker() {
        val projects = viewModel.projects.value
        if (projects.isEmpty()) {
            Toast.makeText(this, "No projects available.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = projects.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose project")
            .setItems(names) { _, which ->
                viewModel.selectProject(names[which])
            }
            .show()
    }

    private fun showCreateProjectDialog() {
        val input = EditText(this).apply {
            hint = "Project name"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("Create project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                hideKeyboard(input)
                viewModel.createProject(input.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ ->
                hideKeyboard(input)
            }
            .show()
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun setWorkspaceVisible(visible: Boolean) {
        val focusedView = currentFocus
        binding.layoutFileManager.isVisible = visible
        binding.viewWorkspaceScrim.isVisible = visible
        binding.wvTerminal.clearFocus()
        binding.etSwipeInput.clearFocus()
        if (binding.wvTerminal.isSelectingText()) {
            binding.wvTerminal.stopTextSelectionMode()
        }
        focusedView?.let(::hideKeyboard)
        applyWorkspaceBackdropBlur(visible)
        if (!visible) {
            restartInputTargets(showKeyboard = false)
        }
    }

    private fun applyWorkspaceBackdropBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = if (enabled) {
                RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
            } else {
                null
            }
            binding.wvTerminal.setRenderEffect(effect)
            binding.bottomPanel.setRenderEffect(effect)
        }

        binding.wvTerminal.alpha = if (enabled) 0.72f else 1f
        binding.bottomPanel.alpha = if (enabled) 0.85f else 1f
    }

    private fun renderProjectHeader(
        projects: List<AgentViewModel.ProjectSummary>,
        selectedProject: String?
    ) {
        val selected = selectedProject ?: "No project"
        val count = projects.firstOrNull { it.name == selected }?.fileCount ?: 0
        binding.tvCurrentProject.text = selected
        binding.tvProjectMeta.text = "$count items"
    }

    private fun openAgentFileExplorer() {
        val project = viewModel.selectedProject.value
        setWorkspaceVisible(false)
        val intent = Intent(this, NotesActivity::class.java).apply {
            putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_AGENT)
            if (!project.isNullOrBlank()) {
                putExtra(NotesActivity.EXTRA_AGENT_ROOT_SUBDIR, project)
            }
        }
        startActivity(intent)
    }

    private suspend fun seedAgentToolFilesIfMissing() {
        val targetDir = OutputDirectoryManager.getAgentToolsFolder(this)
        val assetNames = assets.list("agent_tools").orEmpty()
        assetNames.forEach { assetName ->
            val targetFile = File(targetDir, assetName)
            if (targetFile.exists()) return@forEach
            assets.open("agent_tools/$assetName").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun showAgentToolPicker() {
        lifecycleScope.launch {
            seedAgentToolFilesIfMissing()
            val toolDir = OutputDirectoryManager.getAgentToolsFolder(this@AgentActivity)
            val files = toolDir.listFiles()
                ?.filter {
                    it.isFile &&
                        !it.name.startsWith(".") &&
                        (
                            it.extension.equals("md", ignoreCase = true) ||
                                it.extension.equals("txt", ignoreCase = true)
                            )
                }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()

            if (files.isEmpty()) {
                Toast.makeText(this@AgentActivity, "No tool prompts found yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val labels = files.map { it.nameWithoutExtension }.toTypedArray()
            AlertDialog.Builder(this@AgentActivity)
                .setTitle("Prompt Tools")
                .setItems(labels) { _, which ->
                    insertAgentToolPrompt(files[which])
                }
                .setNeutralButton("Manage") { _, _ ->
                    openAgentToolsExplorer()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun insertAgentToolPrompt(file: File) {
        val content = try {
            file.readText()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read ${file.name}: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (content.isBlank()) {
            Toast.makeText(this, "${file.name} is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!swipeTypingEnabled) {
            swipeTypingEnabled = true
            renderInputMode()
            lifecycleScope.launch {
                prefs.setTerminalSwipeTypingEnabled(true)
            }
        }

        val existing = binding.etSwipeInput.text?.toString().orEmpty()
        val combined = if (existing.isBlank()) {
            content.trim()
        } else {
            existing.trimEnd() + "\n\n" + content.trim()
        }
        binding.etSwipeInput.setText(combined)
        binding.etSwipeInput.setSelection(binding.etSwipeInput.text?.length ?: 0)
        restartInputTargets(showKeyboard = true)
        Toast.makeText(this, "${file.name} inserted into input.", Toast.LENGTH_SHORT).show()
    }

    private fun openAgentToolsExplorer() {
        setWorkspaceVisible(false)
        val intent = Intent(this, NotesActivity::class.java).apply {
            putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_AGENT)
            putExtra(NotesActivity.EXTRA_AGENT_ROOT_SUBDIR, "tools")
        }
        startActivity(intent)
    }

    private fun sendTerminalShortcut(data: String) {
        agentService?.sendTerminalInput(data)
        consumeShiftIfNeeded()
        showKeyboard()
    }

    private fun toggleSwipeTypingMode() {
        swipeTypingEnabled = !swipeTypingEnabled
        renderInputMode()
        lifecycleScope.launch {
            prefs.setTerminalSwipeTypingEnabled(swipeTypingEnabled)
        }
        restartInputTargets(showKeyboard = true)
        Toast.makeText(
            this,
            if (swipeTypingEnabled) "Swipe input bar enabled." else "Raw terminal typing enabled.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleShiftMode() {
        shiftEnabled = !shiftEnabled
        renderShiftMode()
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val target = if (swipeTypingEnabled) binding.etSwipeInput else binding.wvTerminal
        target.postDelayed({
            target.requestFocus()
            imm.restartInput(target)
            imm.showSoftInput(target, 0)
        }, 150)
    }

    private fun restartInputTargets(showKeyboard: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val target = if (swipeTypingEnabled) binding.etSwipeInput else binding.wvTerminal
        target.post {
            renderInputMode()
            target.requestFocus()
            imm.restartInput(target)
            if (showKeyboard) {
                imm.showSoftInput(target, 0)
            }
        }
    }

    private fun toggleSelectionMode() {
        if (binding.wvTerminal.isSelectingText()) {
            binding.wvTerminal.stopTextSelectionMode()
            return
        }

        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_DOWN,
            binding.wvTerminal.width / 2f,
            binding.wvTerminal.height / 2f,
            0
        )
        binding.wvTerminal.startTextSelectionMode(event)
        event.recycle()
        Toast.makeText(this, "Long press terminal text for exact selection handles.", Toast.LENGTH_SHORT).show()
    }

    private fun renderSelectionMode(copyMode: Boolean) {
        binding.btnTermMark.text = if (copyMode) "Done" else "Select"
    }

    private fun renderInputMode() {
        binding.btnTermInputMode.text = if (swipeTypingEnabled) "Swipe On" else "Swipe Off"
        binding.btnTermInputMode.alpha = if (swipeTypingEnabled) 1f else 0.78f
        binding.layoutSwipeInput.isVisible = swipeTypingEnabled
        if (!swipeTypingEnabled) {
            binding.etSwipeInput.text?.clear()
        }
    }

    private fun renderShiftMode() {
        binding.btnTermShift.alpha = if (shiftEnabled) 1f else 0.78f
        binding.btnTermShift.text = if (shiftEnabled) "Shift On" else "Shift"
    }

    private fun submitSwipeInput() {
        val text = binding.etSwipeInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            return
        }

        val transformed = if (shiftEnabled) text.uppercase() else text
        val payload = if (transformed.endsWith("\n") || transformed.endsWith("\r")) {
            transformed
        } else {
            "$transformed\r"
        }
        agentService?.sendTerminalInput(payload)
        binding.etSwipeInput.text?.clear()
        consumeShiftIfNeeded()
        showKeyboard()
    }

    private fun consumeShiftIfNeeded() {
        if (!shiftEnabled) return
        shiftEnabled = false
        runOnUiThread {
            renderShiftMode()
        }
    }

    private fun copyTextToClipboard(text: String) {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) {
            Toast.makeText(this, "Nothing to copy yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Terminal", trimmed))
        Toast.makeText(this, "Terminal text copied.", Toast.LENGTH_SHORT).show()
    }

    private fun resetTerminalSession(replaySnapshot: Boolean) {
        terminalSession = SshTermuxSession(
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            this,
            object : SshTermuxSession.Transport {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    val payload = String(data, offset, count, StandardCharsets.UTF_8)
                    agentService?.sendTerminalInput(payload)
                }

                override fun resize(columns: Int, rows: Int) {
                    agentService?.resizeTerminal(columns, rows, binding.wvTerminal.width, binding.wvTerminal.height)
                }
            }
        )
        binding.wvTerminal.attachSession(terminalSession)
        binding.wvTerminal.requestFocus()
        binding.wvTerminal.updateSize()
        if (replaySnapshot) syncTerminalFromService()
    }

    private fun clearTerminal() {
        resetTerminalSession(replaySnapshot = false)
    }

    private fun writeTerminal(chunk: String) {
        terminalSession.appendRemoteOutput(chunk)
    }

    override fun onScale(scale: Float): Float {
        terminalScale = scale.coerceIn(0.8f, 1.6f)
        binding.wvTerminal.setTextSize((14 * terminalScale).roundToInt())
        return terminalScale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        if (swipeTypingEnabled) {
            binding.etSwipeInput.requestFocus()
        } else {
            binding.wvTerminal.requestFocus()
        }
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = swipeTypingEnabled

    override fun getSoftKeyboardInputType(): Int =
        if (swipeTypingEnabled) {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL or
                android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        runOnUiThread {
            renderSelectionMode(copyMode)
        }
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = shiftEnabled

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        consumeShiftIfNeeded()
        return false
    }

    override fun onEmulatorSet() {
        binding.wvTerminal.setTerminalCursorBlinkerState(true, true)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        runOnUiThread {
            if (binding.wvTerminal.currentSession == changedSession) {
                binding.wvTerminal.onScreenUpdated()
            }
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        runOnUiThread {
            copyTextToClipboard(text)
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        if (text.isNotEmpty()) {
            agentService?.sendTerminalInput(text)
        }
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        runOnUiThread {
            binding.wvTerminal.invalidate()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        runOnUiThread {
            binding.wvTerminal.setTerminalCursorBlinkerState(state, true)
        }
    }

    override fun onTerminalCleared() {
        runOnUiThread {
            clearTerminal()
        }
    }

    override fun onTerminalOutput(chunk: String) {
        runOnUiThread {
            writeTerminal(chunk)
        }
    }

    override fun onServiceError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, e.message, e)
    }

    inner class RemoteFileAdapter(
        private val onClick: (AgentViewModel.RemoteFile) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<RemoteFileAdapter.ViewHolder>() {
        private var items = listOf<AgentViewModel.RemoteFile>()

        fun submitList(newItems: List<AgentViewModel.RemoteFile>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemRemoteFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]
            holder.binding.tvName.text = file.name
            holder.binding.tvInfo.text = when {
                file.isDirectory -> "Folder"
                else -> listOfNotNull(
                    file.sizeBytes?.let(::formatSize),
                    file.updatedAt
                ).joinToString(" • ").ifBlank { "File" }
            }
            holder.binding.ivIcon.setImageResource(
                if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
            )
            when {
                file.isDirectory -> {
                    holder.binding.ivStatus.isVisible = true
                    holder.binding.ivStatus.setImageResource(R.drawable.ic_chevron_right)
                    holder.binding.ivStatus.rotation = 0f
                    holder.binding.ivStatus.imageTintList =
                        android.content.res.ColorStateList.valueOf(getColor(R.color.white_20))
                }
                !file.isDownloaded -> {
                    holder.binding.ivStatus.isVisible = true
                    holder.binding.ivStatus.setImageResource(R.drawable.ic_download)
                    holder.binding.ivStatus.rotation = 0f
                    holder.binding.ivStatus.imageTintList =
                        android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                }
                else -> {
                    holder.binding.ivStatus.isVisible = false
                }
            }
            holder.binding.root.setOnClickListener { onClick(file) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(val binding: ItemRemoteFileBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun restartAgentSession() {
        val email = viewModel.userEmail.value
        val project = viewModel.selectedProject.value
        if (email.isNullOrBlank() || project.isNullOrBlank()) {
            Toast.makeText(this, "No active project to restart.", Toast.LENGTH_SHORT).show()
            return
        }
        agentService?.restart(email, project)
    }

    private fun ensureServiceConnectedForSelectedProject() {
        val service = agentService ?: return
        val email = viewModel.userEmail.value
        val project = viewModel.selectedProject.value
        if (email.isNullOrBlank() || project.isNullOrBlank()) return
        service.connect(email, project)
    }

    private fun syncTerminalFromService() {
        if (!::terminalSession.isInitialized) return
        val snapshot = agentService?.getSnapshot().orEmpty()
        clearTerminal()
        if (snapshot.isNotEmpty()) {
            terminalSession.appendRemoteOutput(snapshot)
        }
    }
}
