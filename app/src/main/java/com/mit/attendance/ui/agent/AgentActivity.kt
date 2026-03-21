package com.mit.attendance.ui.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R
import com.mit.attendance.auth.ChatGptLoginManager
import com.mit.attendance.auth.LoginActivity
import com.mit.attendance.databinding.ActivityAgentBinding
import com.mit.attendance.databinding.ItemChatBinding
import com.mit.attendance.databinding.ItemTaskHistoryBinding
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.launch

class AgentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentBinding
    private val viewModel: AgentViewModel by viewModels()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var loginManager: ChatGptLoginManager

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            initAgent()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loginManager = ChatGptLoginManager(this)

        setupUI()
        observeViewModel()

        if (loginManager.isLoggedIn()) {
            initAgent()
        } else {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.hiddenWebviewContainer.isVisible -> {
                        binding.hiddenWebviewContainer.isVisible = false
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun initAgent() {
        viewModel.init(binding.hiddenWebviewContainer, loginManager)
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        chatAdapter = ChatAdapter()
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.adapter = chatAdapter

        historyAdapter = HistoryAdapter { folder ->
            viewModel.selectFolderToResume(folder)
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                binding.etMessage.text.clear()
            }
        }

        binding.btnNewTaskCard.setOnClickListener {
            viewModel.startNewTask()
        }

        binding.btnRun.setOnClickListener { viewModel.confirmAndRun() }
        binding.btnCancel.setOnClickListener { viewModel.cancel() }
        binding.btnRestart.setOnClickListener { viewModel.restart() }
        
        binding.btnResume.setOnClickListener { viewModel.resumeRun() }
        binding.btnDiscardResume.setOnClickListener { 
            viewModel.resume.value?.outputDir?.let { path ->
                viewModel.discardCheckpoint(path)
            }
        }

        binding.btnGptToggle.setOnClickListener {
            binding.hiddenWebviewContainer.isVisible = !binding.hiddenWebviewContainer.isVisible
        }

        binding.btnFilesToggle.setOnClickListener {
            startActivity(Intent(this, AgentFileExplorerActivity::class.java))
        }

        binding.btnPauseToggle.setOnClickListener {
            viewModel.togglePause()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.phase.collect { updateUiForPhase(it) } }
                launch {
                    viewModel.chat.collect { messages ->
                        chatAdapter.submitList(messages)
                        if (messages.isNotEmpty()) {
                            binding.rvChat.smoothScrollToPosition(messages.size - 1)
                        }
                        updateHistoryAndChatVisibility()
                    }
                }
                launch { viewModel.pipeline.collect { updatePipelineUi(it) } }
                launch {
                    viewModel.resume.collect { resumeInfo ->
                        binding.cardResume.isVisible = resumeInfo != null
                        binding.tvResumeLabel.text = resumeInfo?.label
                        updateHistoryAndChatVisibility()
                    }
                }
                launch {
                    viewModel.history.collect { folders ->
                        historyAdapter.submitList(folders.filter { !it.isCompleted })
                    }
                }
                launch { viewModel.isNewTaskSession.collect { updateHistoryAndChatVisibility() } }
                launch {
                    viewModel.isPaused.collect { isPaused ->
                        binding.btnPauseToggle.text = if (isPaused) "START AGENT" else "STOP AGENT"
                        binding.btnPauseToggle.setBackgroundColor(
                            if (isPaused) getColor(R.color.green) else getColor(R.color.accent)
                        )
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

    private fun updateUiForPhase(phase: AgentViewModel.Phase) {
        binding.pbLoading.isVisible = phase == AgentViewModel.Phase.LOADING
        val isConfigOrReady = phase == AgentViewModel.Phase.CONFIGURING || phase == AgentViewModel.Phase.READY_TO_RUN
        binding.layoutInput.isVisible = isConfigOrReady
        binding.btnRun.isVisible = phase == AgentViewModel.Phase.READY_TO_RUN
        binding.cardPipeline.isVisible = phase == AgentViewModel.Phase.RUNNING
        updateHistoryAndChatVisibility()
        binding.btnRestart.isVisible = phase == AgentViewModel.Phase.DONE || phase == AgentViewModel.Phase.READY_TO_RUN || viewModel.resume.value != null
    }

    private fun updateHistoryAndChatVisibility() {
        val phase = viewModel.phase.value
        val isNew = viewModel.isNewTaskSession.value
        val chatEmpty = viewModel.chat.value.isEmpty()
        val hasResume = viewModel.resume.value != null
        binding.layoutHistory.isVisible = !isNew && !hasResume && phase == AgentViewModel.Phase.CONFIGURING && chatEmpty
        binding.rvChat.isVisible = (isNew || !chatEmpty) && !hasResume
        binding.btnRestart.isVisible = phase == AgentViewModel.Phase.DONE || phase == AgentViewModel.Phase.READY_TO_RUN || hasResume
    }

    private fun updatePipelineUi(state: AgentViewModel.PipelineState?) {
        if (state == null) return
        binding.tvCurrentFilename.text = "Current: ${state.currentFilename}"
        binding.tvStage.text = state.currentStage
        binding.pbTask.progress = state.taskPercent
        binding.tvTaskProgress.text = "Tasks: ${state.tasksDone}/${state.totalTasks} (${state.taskPercent}%)"
        binding.pbSubtask.progress = state.subtaskPercent
    }

    inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
        private var items = listOf<AgentViewModel.ChatMessage>()
        fun submitList(newItems: List<AgentViewModel.ChatMessage>) {
            items = newItems
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            holder.binding.tvMessage.text = m.text
            if (m.role == "user") {
                holder.binding.layoutChatBubble.gravity = android.view.Gravity.END
                holder.binding.cardMessage.strokeColor = getColor(R.color.accent)
            } else {
                holder.binding.layoutChatBubble.gravity = android.view.Gravity.START
                holder.binding.cardMessage.strokeColor = getColor(R.color.white_20)
            }
        }
        override fun getItemCount() = items.size
        inner class VH(val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root)
    }

    inner class HistoryAdapter(private val onClick: (OutputDirectoryManager.TaskFolder) -> Unit) : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items = listOf<OutputDirectoryManager.TaskFolder>()
        fun submitList(newItems: List<OutputDirectoryManager.TaskFolder>) {
            items = newItems
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTaskHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val folder = items[position]
            holder.binding.tvTaskName.text = folder.name.replace("_", " ")
            holder.binding.tvTaskStatus.text = "Click to Resume"
            holder.binding.root.setOnClickListener { onClick(folder) }
        }
        override fun getItemCount() = items.size
        inner class VH(val binding: ItemTaskHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
