package com.mit.attendance.ui.subjects

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.*
import android.webkit.*
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mit.attendance.R
import com.mit.attendance.auth.GoogleAccountBackupManager
import com.mit.attendance.auth.GitHubAccountStore
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivityFileExplorerBinding
import com.mit.attendance.databinding.ActivityErpWebviewBinding
import com.mit.attendance.databinding.ItemFileExplorerBinding
import com.mit.attendance.storage.OutputDirectoryManager
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.hslf.usermodel.HSLFShape
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class NotesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "explorer_type"
        const val EXTRA_INITIAL_RELATIVE_PATH = "initial_relative_path"
        const val EXTRA_NOTES_FOLDER = "notes_folder"
        const val EXTRA_NOTES_SUBJECT = "notes_subject"
        const val TYPE_NOTES = "notes"
        const val TYPE_LOCAL_NOTES = "local_notes"
        const val TYPE_UPDATE = "update"
    }

    private lateinit var binding: ActivityFileExplorerBinding
    private lateinit var explorerAdapter: FileExplorerAdapter
    private var currentDirectory: File? = null
    private var rootDirectory: File? = null
    private var selectedFile: File? = null
    private var pendingUris = mutableListOf<Uri>()
    private var cutFiles: List<File>? = null
    private var explorerType = TYPE_NOTES
    private var isEditorContentLoading = false
    private var editorDirty = false

    private lateinit var repo: AttendanceRepository
    private val githubToken: String get() = GitHubAccountStore(this).account()?.token.orEmpty()
    private var notesSyncRunning = false

    private var isEditMode = false
    private val selectedFiles = mutableSetOf<File>()

    // Long Press Menu state
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPressActive = false
    private var selectedMenuIndex = -1
    private lateinit var menuItems: List<TextView>

    private var lastY = 0f
    private var accumulatedDeltaY = 0f
    private val swipeThreshold = 100f
    private var velocityTracker: VelocityTracker? = null

    private var activeWebView: WebView? = null
    private lateinit var markwon: Markwon
    private var chatHistoryText = ""
    private var currentNotebookId: String? = null
    private var hasMoved = false
    private val movementThreshold = 15f
    private var initialRelativePath: String? = null
    private var initialTargetOpened = false
    private var pendingPythonPickerFieldId: String? = null
    private var pythonRunnerMode: String = "input"
    private val pythonFieldViews = linkedMapOf<String, PythonFieldViewState>()
    private var textEditorFile: File? = null
    private val longPressRunnable = Runnable {
        showLongPressMenu()
    }

    private val chatQuestions = mutableListOf<String>()

    private val pythonFilePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handlePythonFilePicked(uri)
        }

    private data class PythonInputSpec(
        val id: String,
        val mode: String,
        val kind: String,
        val label: String,
        val choices: List<String>,
        val required: Boolean = false,
        val defaultValue: String? = null,
        val helpText: String? = null,
        val argNames: List<String> = emptyList(),
        val isOptional: Boolean = false,
        val action: String? = null,
        val multiple: Boolean = false,
        val typeName: String? = null
    )

    private data class PythonFieldViewState(
        val spec: PythonInputSpec,
        val textInput: EditText? = null,
        val fileValue: TextView? = null,
        val choiceGroup: RadioGroup? = null,
        val boolInput: CheckBox? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = AttendanceRepository(this)
        explorerType = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_NOTES
        initialRelativePath = intent.getStringExtra(EXTRA_INITIAL_RELATIVE_PATH)?.takeIf { it.isNotBlank() }

        val account = GitHubAccountStore(this).account()
        val notesFolder = intent.getStringExtra(EXTRA_NOTES_FOLDER)?.let { File(it).canonicalFile }
        if (explorerType == TYPE_NOTES) {
            val accountRoot = account?.let { File(OutputDirectoryManager.getNotesFolder(this), it.username.lowercase(Locale.ROOT)).canonicalFile }
            if (notesFolder == null || accountRoot == null || !notesFolder.isDirectory ||
                !notesFolder.path.startsWith(accountRoot.path + File.separator)) {
                startActivity(Intent(this, NotesLibraryActivity::class.java))
                finish()
                return
            }
        }

        rootDirectory = when(explorerType) {
            TYPE_UPDATE -> OutputDirectoryManager.getUpdatesFolder(this)
            TYPE_NOTES -> notesFolder!!
            else -> OutputDirectoryManager.getNotesFolder(this) 
        }
        currentDirectory = rootDirectory

        markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .build()

        setupEditor()
        setupUI()
        handleIntent(intent)

        menuItems = listOf(
            binding.tvMenuFolder,
            binding.tvMenuFile,
            binding.tvMenuUrl,
            binding.tvMenuEdit,
            binding.tvMenuNotebook
        )

        WebViewWindowManager.init(this)

        browseFiles(currentDirectory)
    }

    private fun pushNotes() = syncNotes(true)
    private fun pullNotes() = syncNotes(false)

    private fun syncNotes(push: Boolean, choices: JSONObject? = null) {
        if (notesSyncRunning || explorerType != TYPE_NOTES) return
        if (editorDirty) {
            Toast.makeText(this, "Save your open file before syncing.", Toast.LENGTH_LONG).show()
            return
        }
        val account = GitHubAccountStore(this).account() ?: run {
            startActivity(Intent(this, NotesLibraryActivity::class.java))
            return
        }
        notesSyncRunning = true
        val progress = AlertDialog.Builder(this)
            .setMessage(if (push) "Saving and pushing private notes…" else "Getting notes updates…")
            .setCancelable(false).show()
        lifecycleScope.launch {
            var continueSync = false
            try {
                val result = withContext(Dispatchers.IO) {
                    ensurePythonStarted()
                    val module = Python.getInstance().getModule("notes_git")
                    val value = if (choices == null) module.callAttr("sync",
                        rootDirectory!!.absolutePath, account.token, account.username, push)
                    else module.callAttr("resolve", rootDirectory!!.absolutePath,
                        account.token, account.username, choices.toString())
                    JSONObject(value.toString())
                }
                check(result.optBoolean("ok")) { result.optString("message") }
                browseFiles(currentDirectory)
                val conflicts = result.optJSONArray("conflicts")
                if (conflicts != null && conflicts.length() > 0) {
                    resolveNotesConflicts((0 until conflicts.length()).map { conflicts.getString(it) }, push)
                } else if (choices != null) {
                    continueSync = true
                } else Toast.makeText(this@NotesActivity, result.optString("message"), Toast.LENGTH_LONG).show()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(this@NotesActivity, error.message ?: "Notes sync failed", Toast.LENGTH_LONG).show()
            } finally {
                notesSyncRunning = false
                progress.dismiss()
            }
            if (continueSync) syncNotes(push)
        }
    }

    private fun resolveNotesConflicts(paths: List<String>, push: Boolean) {
        val choices = JSONObject()
        fun choose(index: Int) {
            if (index == paths.size) { syncNotes(push, choices); return }
            AlertDialog.Builder(this)
                .setTitle("Notes conflict ${index + 1}/${paths.size}")
                .setMessage(paths[index] + "\nChoose which version to keep. Both versions remain in Git history.")
                .setPositiveButton("Keep mine") { _, _ -> choices.put(paths[index], "mine"); choose(index + 1) }
                .setNegativeButton("Use incoming") { _, _ -> choices.put(paths[index], "class"); choose(index + 1) }
                .setNeutralButton("Keep both") { _, _ -> choices.put(paths[index], "both"); choose(index + 1) }
                .setCancelable(true).show()
        }
        choose(0)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (binding.chatContainer.isVisible) {
            handler.removeCallbacks(longPressRunnable)
            return super.dispatchTouchEvent(event)
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val bottomAreaStart = screenHeight * 0.95f
        val isStartedInBottomArea = event.rawY >= bottomAreaStart

        if (WebViewWindowManager.handleTouchEvent(event, binding.webViewContainer)) {
            handler.removeCallbacks(longPressRunnable)
            return true
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        val screenWidth = resources.displayMetrics.widthPixels

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isStartedInBottomArea) {
                    handler.postDelayed(longPressRunnable, 500)
                }
                hasMoved = false
                lastY = event.rawY
                accumulatedDeltaY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val movementY = abs(event.rawY - lastY)
                if (!isLongPressActive && !hasMoved && movementY > movementThreshold) {
                    hasMoved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (isLongPressActive) {
                    if (event.rawX > screenWidth / 2) {
                        val deltaY = lastY - event.rawY
                        lastY = event.rawY
                        accumulatedDeltaY += deltaY

                        velocityTracker?.computeCurrentVelocity(1000)
                        val velocityY = velocityTracker?.yVelocity ?: 0f
                        val multiplier = if (abs(velocityY) > 2000) 2 else 1

                        if (abs(accumulatedDeltaY) > swipeThreshold) {
                            if (accumulatedDeltaY > 0) {
                                moveSelectionUp(multiplier)
                            } else {
                                moveSelectionDown(multiplier)
                            }
                            accumulatedDeltaY = 0f
                        }
                    } else {
                        val deltaY = abs(event.rawY - lastY)
                        if (!hasMoved && deltaY > movementThreshold) {
                            hasMoved = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                    return true
                }
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (isLongPressActive) {
                    val finalSelection = selectedMenuIndex
                    hideLongPressMenu()
                    if (event.action == MotionEvent.ACTION_UP) {
                        executeMenuAction(finalSelection)
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun showLongPressMenu() {
        if (isEditMode) return
        isLongPressActive = true
        binding.blurOverlay.visibility = View.VISIBLE
        binding.flMenuContainer.visibility = View.VISIBLE

        selectedMenuIndex = 1
        binding.root.post {
            updateMenuUI(immediate = true)
        }
    }

    private fun hideLongPressMenu() {
        isLongPressActive = false
        binding.blurOverlay.visibility = View.GONE
        binding.flMenuContainer.visibility = View.GONE

        menuItems.forEach {
            it.animate().scaleX(1.0f).scaleY(1.0f).translationX(0f).setDuration(0).start()
        }
        selectedMenuIndex = -1
    }

    private fun executeMenuAction(index: Int) {
        val currentUrl = activeWebView?.url
        when (index) {
            0 -> showCreateFolderDialog()
            1 -> showCreateFileDialog()
            2 -> showCreateUrlDialog(currentUrl)
            3 -> toggleEditMode(true)
            4 -> {
                openNotebookLmWithRestore()
            }
        }
    }

    private fun openNotebookLmWithRestore() {
        lifecycleScope.launch {
            if (isAlreadyLoggedIn()) {
                Log.d("NLM", "✅ Already logged in, skipping WebView")
                startAPI()
                return@launch
            }

            val accountToken = GitHubAccountStore(this@NotesActivity).account()?.token
            val restored = accountToken != null && GoogleAccountBackupManager.restoreAllGoogleAccountsIfAvailable(
                context = this@NotesActivity,
                prefs = repo.prefs,
                githubToken = accountToken
            )

            if (restored && isAlreadyLoggedIn()) {
                Log.d("NLM", "✅ Restored Google accounts from backup repo")
                Toast.makeText(this@NotesActivity, "Google accounts restored from backup", Toast.LENGTH_SHORT).show()
                startAPI()
            } else {
                Log.d("NLM", "❌ Need login, showing WebView")
                openNotebookLM()
            }
        }
    }

    private fun openNotebookLM() {
        val notebookUrl = "https://notebooklm.google.com/"
        val webBinding = ActivityErpWebviewBinding.inflate(layoutInflater, binding.webViewContainer, false)
        webBinding.btnBack.visibility = View.VISIBLE
        webBinding.btnBack.setOnClickListener {
            binding.webViewContainer.removeView(webBinding.root)
            WebViewWindowManager.onViewRemoved(webBinding.root)
            if (activeWebView == webBinding.webView) activeWebView = null
        }

        activeWebView = webBinding.webView
        webBinding.toolbar.visibility = View.GONE

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webBinding.webView, true)

        webBinding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        webBinding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                webBinding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                webBinding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()

                if (view == null || url == null) return
                Log.d("NLM", "Page: $url")

                when {
                    url.contains("notebooklm.google.com") &&
                    !url.contains("accounts.google.com") &&
                    !url.contains("/signin") &&
                    !url.contains("/v3/signin") -> {

                        val cookies = CookieManager.getInstance().getCookie("https://notebooklm.google.com")
                        if (cookies != null && cookies.contains("SID=")) {
                            Log.d("NLM", "✅ Login detected!")
                            lifecycleScope.launch {
                                val saved = saveCookies(view)
                                if (saved) {
                                    binding.webViewContainer.removeView(webBinding.root)
                                    WebViewWindowManager.onViewRemoved(webBinding.root)
                                    if (activeWebView == webBinding.webView) activeWebView = null
                                    startAPI()
                                }
                            }
                        }
                    }
                    url.contains("accounts.google.com") -> {
                        Log.d("NLM", "⏳ Waiting for login...")
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        webBinding.webView.loadUrl(notebookUrl)
        WebViewWindowManager.addWebView(binding.webViewContainer, webBinding.root)
    }

    private suspend fun saveCookies(view: WebView): Boolean {
        val url = view.url ?: return false
        return withContext(Dispatchers.IO) {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url) ?: return@withContext false

            if (!cookies.contains("SID=")) return@withContext false

            try {
                val storageFile = File(getExternalFilesDir(null), "storage_state.json")
                val json = JSONObject()
                val cookiesArray = JSONArray()

                cookies.split(";").forEach { cookie ->
                    val parts = cookie.split("=")
                    if (parts.size >= 2) {
                        val cookieObj = JSONObject()
                        cookieObj.put("name", parts[0].trim())
                        cookieObj.put("value", parts[1].trim())
                        cookieObj.put("domain", ".google.com")
                        cookieObj.put("path", "/")
                        cookiesArray.put(cookieObj)
                    }
                }

                json.put("cookies", cookiesArray)
                json.put("origins", JSONArray())
                storageFile.writeText(json.toString(4))

                val backupResult = GoogleAccountBackupManager.backupAllGoogleAccounts(
                    context = this@NotesActivity,
                    prefs = repo.prefs,
                    githubToken = githubToken
                )
                if (backupResult.isFailure) {
                    Log.e("NLM", "Google backup sync failed", backupResult.exceptionOrNull())
                }
                return@withContext true
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    private fun isAlreadyLoggedIn(): Boolean {
        val storageFile = File(getExternalFilesDir(null), "storage_state.json")
        if (!storageFile.exists()) return false

        val ageMs = System.currentTimeMillis() - storageFile.lastModified()
        val ageDays = ageMs / (1000 * 60 * 60 * 24)

        if (ageDays > 7) {
            Log.d("NLM", "Cookies too old, re-login needed")
            storageFile.delete()
            return false
        }
        return true
    }

    private fun startAPI() {
        ensurePythonStarted()
        val storageFile = File(getExternalFilesDir(null), "storage_state.json")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                module.callAttr("set_storage_path", storageFile.absolutePath)

                withContext(Dispatchers.Main) {
                    val savedIdFile = File(currentDirectory, ".notebook_id")
                    if (savedIdFile.exists()) {
                        val notebookId = savedIdFile.readText().trim()
                        if (notebookId.isNotEmpty()) {
                            showFloatingChat(notebookId)
                        } else {
                            showNotebookSelector()
                        }
                    } else {
                        showNotebookSelector()
                    }
                }
            } catch (e: Exception) {
                Log.e("NLM", "Error starting API", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "API Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showFloatingChat(notebookId: String) {
        if (currentNotebookId != notebookId) {
            chatHistoryText = ""
            binding.tvChatHistory.text = ""
            chatQuestions.clear()
            currentNotebookId = notebookId

            // Load local history from file in the current folder
            try {
                val historyFile = File(currentDirectory, ".chat_history")
                if (historyFile.exists()) {
                    chatHistoryText = historyFile.readText()
                    if (chatHistoryText.isNotEmpty()) {
                        markwon.setMarkdown(binding.tvChatHistory, chatHistoryText)
                        // Rebuild question index
                        chatHistoryText.lines().forEachIndexed { i, line ->
                            if (line == "### You") {
                                val q = chatHistoryText.lines().getOrElse(i + 1) { "" }
                                if (q.isNotEmpty()) chatQuestions.add(q)
                            }
                        }
                        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            } catch (e: Exception) {
                Log.e("Chat", "Failed to load local history: ${e.message}")
            }
        }

        binding.chatContainer.visibility = View.VISIBLE
        binding.etChatInput.requestFocus()

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                chatQuestions.add(text)
                appendChat("### You\n$text")
                binding.etChatInput.setText("")
                performChatAsk(notebookId, text)
            }
        }

        binding.btnChatIndex.setOnClickListener {
            showChatIndex()
        }

        binding.chatContainer.setOnLongClickListener {
            binding.chatContainer.visibility = View.GONE
            true
        }
    }

    private fun showChatIndex() {
        if (chatQuestions.isEmpty()) {
            Toast.makeText(this, "No questions yet", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Chat Index")
            .setItems(chatQuestions.toTypedArray()) { _, which ->
                jumpToQuestion(chatQuestions[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun jumpToQuestion(question: String) {
        val query = "### You\n$question"
        val rawIndex = chatHistoryText.indexOf(query)
        if (rawIndex == -1) return

        // Calculate scroll position as a proportion of the raw text
        val ratio = rawIndex.toFloat() / chatHistoryText.length.toFloat()

        binding.chatScroll.post {
            val totalScrollRange = binding.chatScroll.getChildAt(0).height - binding.chatScroll.height
            val targetY = (ratio * totalScrollRange).toInt()
            binding.chatScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun appendChat(text: String) {
        chatHistoryText = if (chatHistoryText.isEmpty()) text else "$chatHistoryText\n\n$text"
        markwon.setMarkdown(binding.tvChatHistory, chatHistoryText)

        // Save to local file in the folder
        try {
            val historyFile = File(currentDirectory, ".chat_history")
            historyFile.writeText(chatHistoryText)
        } catch (e: Exception) {
            Log.e("Chat", "Failed to save history: ${e.message}")
        }

        binding.chatScroll.post {
            binding.chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun performChatAsk(notebookId: String, question: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                val answer = module.callAttr("ask", notebookId, question).toString()

                withContext(Dispatchers.Main) {
                    appendChat("### NotebookLM\n$answer")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendChat("**Error:** ${e.message}")
                }
            }
        }
    }

    private fun ensurePythonStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun showNotebookSelector() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                val notebooksJson = module.callAttr("get_notebooks").toString()
                val notebooksArray = JSONArray(notebooksJson)

                if (notebooksArray.length() == 0) {
                    withContext(Dispatchers.Main) {
                        showCreateNotebookDialog()
                    }
                    return@launch
                }

                val names = mutableListOf<String>()
                val ids = mutableListOf<String>()
                for (i in 0 until notebooksArray.length()) {
                    val obj = notebooksArray.getJSONObject(i)
                    names.add(obj.getString("title"))
                    ids.add(obj.getString("id"))
                }

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@NotesActivity)
                        .setTitle("Select Notebook")
                        .setItems(names.toTypedArray()) { _, which ->
                            val selectedId = ids[which]
                            saveNotebookIdForFolder(selectedId)
                            showFloatingChat(selectedId)
                        }
                        .setNeutralButton("New") { _, _ -> showCreateNotebookDialog() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e("NLM", "Failed to load notebooks", e)
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@NotesActivity)
                        .setTitle("Load Failed")
                        .setMessage("Failed to load notebooks: ${e.message}\n\nWould you like to create a new one?")
                        .setPositiveButton("Create New") { _, _ -> showCreateNotebookDialog() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun saveNotebookIdForFolder(notebookId: String) {
        try {
            val file = File(currentDirectory, ".notebook_id")
            file.writeText(notebookId)
        } catch (e: Exception) {
            Log.e("NLM", "Failed to save notebook ID", e)
        }
    }

    private fun uploadCurrentFolder(notebookId: String) {
        val root = currentDirectory ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val py = Python.getInstance()
            val module = py.getModule("bridge")

            val allFiles = mutableListOf<File>()
            root.walkTopDown().forEach { file ->
                if (!file.isDirectory && !file.name.startsWith(".")) {
                    allFiles.add(file)
                }
            }

            if (allFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "No files to upload", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@NotesActivity, "Uploading ${allFiles.size} files recursively...", Toast.LENGTH_SHORT).show()
            }

            var success = 0
            allFiles.forEach { file ->
                try {
                    if (file.name.endsWith(".url")) {
                        val url = file.readText().trim()
                        if (url.isNotEmpty()) {
                            module.callAttr("add_url", notebookId, url)
                            success++
                        }
                    } else {
                        module.callAttr("upload_file", notebookId, file.absolutePath)
                        success++
                    }
                } catch (e: Exception) {
                    Log.e("NLM", "Failed to upload ${file.name}", e)
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@NotesActivity, "Uploaded $success/${allFiles.size} files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateNotebookDialog() {
        val editText = EditText(this).apply {
            hint = "Notebook Name"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    createNotebook(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNotebook(name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                val id = module.callAttr("create_notebook", name).toString()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "Created notebook: $id", Toast.LENGTH_SHORT).show()
                    saveNotebookIdForFolder(id)
                    showFloatingChat(id)
                }
            } catch (e: Exception) {
                Log.e("NLM", "Failed to create notebook", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "Failed to create notebook: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sanitizeRepoName(name: String): String {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9\\-]"), "-")
            .replace(Regex("-+"), "-")
        return sanitized.trim('-')
    }

    private fun updateGitButtonsVisibility() {
        val visible = !isEditMode && selectedFile == null && explorerType == TYPE_NOTES
        binding.btnPush.isVisible = visible
        binding.btnPull.isVisible = visible
        binding.btnBackupAccounts.isVisible = visible
        binding.btnPush.contentDescription = "Push my private notes"
        binding.btnPull.contentDescription = "Get class and personal notes updates"
    }

    private fun toggleEditMode(enabled: Boolean) {
        isEditMode = enabled
        selectedFiles.clear()
        binding.btnDeleteFiles.isVisible = enabled
        binding.btnCutFiles.isVisible = enabled
        binding.btnShareFile.isVisible = enabled || (selectedFile != null)
        binding.btnPasteFile.isVisible = (pendingUris.isNotEmpty() || cutFiles != null) && !enabled

        if (!enabled) {
            binding.toolbar.title = currentDirectory?.name ?: "Notes"
            updateGitButtonsVisibility()
        } else {
            updateEditTitle()
            binding.btnPush.isVisible = false
            binding.btnPull.isVisible = false
            binding.btnBackupAccounts.isVisible = false
        }

        updatePythonRunVisibility()
        explorerAdapter.notifyDataSetChanged()
    }

    private fun updateEditTitle() {
        binding.toolbar.title = "${selectedFiles.size} selected"
    }

    private fun showCreateFolderDialog() {
        val editText = EditText(this).apply {
            hint = "Folder Name"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newDir = File(currentDirectory ?: rootDirectory!!, name)
                    if (newDir.mkdir()) {
                        browseFiles(currentDirectory)
                    } else {
                        Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateFileDialog() {
        val editText = EditText(this).apply {
            hint = "File Name"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("New File")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                var name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (!name.endsWith(".txt")) name += ".txt"
                    val newFile = File(currentDirectory ?: rootDirectory!!, name)
                    try {
                        if (newFile.createNewFile()) {
                            browseFiles(currentDirectory)
                        } else {
                            Toast.makeText(this, "File already exists", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateUrlDialog(initialUrl: String? = null) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val nameInput = EditText(this).apply { hint = "Name" }
        val urlInput = EditText(this).apply {
            hint = "URL"
            setText(initialUrl)
        }
        layout.addView(nameInput)
        layout.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("New URL Shortcut")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val newFile = File(currentDirectory ?: rootDirectory!!, "$name.url")
                    try {
                        newFile.writeText(url)
                        browseFiles(currentDirectory)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveSelectionUp(steps: Int) {
        val newIndex = (selectedMenuIndex - steps).coerceAtLeast(0)
        if (newIndex != selectedMenuIndex) {
            selectedMenuIndex = newIndex
            updateMenuUI()
        }
    }

    private fun moveSelectionDown(steps: Int) {
        val newIndex = (selectedMenuIndex + steps).coerceAtMost(menuItems.size - 1)
        if (newIndex != selectedMenuIndex) {
            selectedMenuIndex = newIndex
            updateMenuUI()
        }
    }

    private fun updateMenuUI(immediate: Boolean = false) {
        if (selectedMenuIndex < 0 || selectedMenuIndex >= menuItems.size) return

        val targetItem = menuItems[selectedMenuIndex]
        binding.vSelector.visibility = View.VISIBLE

        val selectorY = targetItem.y + binding.llLongPressMenu.y
        val selectorX = binding.llLongPressMenu.x - 10f

        if (immediate) {
            binding.vSelector.x = selectorX
            binding.vSelector.y = selectorY
            menuItems.forEachIndexed { index, view ->
                if (index == selectedMenuIndex) {
                    view.scaleX = 1.2f
                    view.scaleY = 1.2f
                    view.translationX = 30f
                } else {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    view.translationX = 0f
                }
            }
        } else {
            binding.vSelector.animate()
                .x(selectorX)
                .y(selectorY)
                .setDuration(150)
                .start()

            menuItems.forEachIndexed { index, view ->
                if (index == selectedMenuIndex) {
                    view.animate().scaleX(1.2f).scaleY(1.2f).translationX(30f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).translationX(0f).setDuration(150).start()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    pendingUris.clear()
                    pendingUris.add(uri)
                    binding.btnPasteFile.isVisible = true
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (uris != null) {
                    pendingUris.clear()
                    pendingUris.addAll(uris)
                    binding.btnPasteFile.isVisible = true
                }
            }
        }
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            if (isEditMode) {
                toggleEditMode(false)
            } else if (selectedFile != null) {
                attemptCloseSelectedFile()
            } else if (currentDirectory != rootDirectory) {
                navigateUp()
            } else {
                finish()
            }
        }

        explorerAdapter = FileExplorerAdapter(
            onFileClick = { file ->
                if (file.name == ".__settings__") {
                    showSettingsMenu()
                } else if (isEditMode) {
                    toggleSelection(file)
                } else {
                    openFile(file)
                }
            },
            onRenameClick = { file -> showRenameDialog(file) }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = explorerAdapter

        binding.btnShareFile.setOnClickListener {
            if (isEditMode) shareSelectedFiles() else selectedFile?.let { shareFile(it) }
        }

        binding.btnSaveFile.setOnClickListener {
            saveEditedFile()
        }

        binding.btnRunPython.setOnClickListener {
            val file = selectedFile
            if (file == null || file.extension.lowercase() != "py") {
                Toast.makeText(this, "Open a Python file first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showPythonRunDialog(file)
        }

        binding.btnPasteFile.setOnClickListener {
            if (pendingUris.isNotEmpty()) savePendingFiles() else if (cutFiles != null) pasteFiles()
        }

        binding.btnDeleteFiles.setOnClickListener { confirmDeleteSelectedFiles() }

        binding.btnCutFiles.setOnClickListener {
            if (selectedFiles.isNotEmpty()) {
                cutFiles = selectedFiles.toList()
                toggleEditMode(false)
                Toast.makeText(this, "Files cut. Navigate and paste.", Toast.LENGTH_SHORT).show()
                binding.btnPasteFile.isVisible = true
            }
        }

        binding.btnPush.setOnClickListener { pushNotes() }
        binding.btnPull.setOnClickListener { pullNotes() }
        binding.btnBackupAccounts.setOnClickListener { showBackupAccountsDialog() }
        updatePythonRunVisibility()
        updateEditorActions()
    }

    private fun showBackupAccountsDialog() {
        if (GitHubAccountStore(this).account() == null) {
            startActivity(Intent(this, NotesLibraryActivity::class.java))
            return
        }
        if (!File(getExternalFilesDir(null), "storage_state.json").exists()) {
            Toast.makeText(this, "Log into Google first, then back up all accounts", Toast.LENGTH_LONG).show()
            openNotebookLM()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Backup All Accounts")
            .setMessage("Save the full shared Google WebView state for later restore?")
            .setPositiveButton("Backup") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = GoogleAccountBackupManager.backupAllGoogleAccounts(
                        context = this@NotesActivity,
                        prefs = repo.prefs,
                        githubToken = githubToken
                    )
                    withContext(Dispatchers.Main) {
                        result
                            .onSuccess {
                                Toast.makeText(
                                    this@NotesActivity,
                                    "Backed up all Google accounts",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(
                                    this@NotesActivity,
                                    "Backup failed: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEditorActions() {
        binding.btnSaveFile.isVisible = textEditorFile != null
        binding.btnSaveFile.alpha = if (editorDirty) 1f else 0.5f
        binding.btnSaveFile.isEnabled = editorDirty
    }

    private fun attemptCloseSelectedFile() {
        if (textEditorFile == null || !editorDirty) {
            closeFile()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save changes to ${textEditorFile?.name} before closing?")
            .setPositiveButton("Save") { _, _ ->
                if (saveEditedFile()) {
                    closeFile()
                }
            }
            .setNegativeButton("Discard") { _, _ ->
                closeFile()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showSettingsMenu() {
        val options = arrayOf("Delete Chat History", "Delete Notebook (Server + Local)", "Unlink Folder from Notebook", "Add Sources (from folder)", "Remove Sources")
        AlertDialog.Builder(this)
            .setTitle("Notebook Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        chatHistoryText = ""
                        binding.tvChatHistory.text = ""
                        chatQuestions.clear()
                        try {
                            val historyFile = File(currentDirectory, ".chat_history")
                            if (historyFile.exists()) historyFile.delete()
                        } catch (e: Exception) {
                            Log.e("Chat", "Failed to delete chat history file: ${e.message}")
                        }
                        Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
                    }
                    1 -> confirmDeleteNotebook()
                    2 -> unlinkFolder()
                    3 -> showAddSourcesDialog()
                    4 -> showRemoveSourcesDialog()
                }
            }
            .show()
    }

    private fun unlinkFolder() {
        val savedIdFile = File(currentDirectory, ".notebook_id")
        if (savedIdFile.exists()) {
            savedIdFile.delete()
            chatHistoryText = ""
            binding.tvChatHistory.text = ""
            chatQuestions.clear()
            currentNotebookId = null
            binding.chatContainer.visibility = View.GONE
            Toast.makeText(this, "Folder unlinked", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No notebook linked to this folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteNotebook() {
        val savedIdFile = File(currentDirectory, ".notebook_id")
        if (!savedIdFile.exists()) {
            Toast.makeText(this, "No notebook linked to this folder", Toast.LENGTH_SHORT).show()
            return
        }
        val notebookId = savedIdFile.readText().trim()

        AlertDialog.Builder(this)
            .setTitle("Delete Notebook?")
            .setMessage("This will delete the notebook from NotebookLM server AND unlink this folder.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val py = Python.getInstance()
                        val module = py.getModule("bridge")
                        module.callAttr("delete_notebook", notebookId)
                        savedIdFile.delete()
                        withContext(Dispatchers.Main) {
                            chatHistoryText = ""
                            binding.tvChatHistory.text = ""
                            chatQuestions.clear()
                            currentNotebookId = null
                            binding.chatContainer.visibility = View.GONE
                            Toast.makeText(this@NotesActivity, "Notebook deleted from server", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("NLM", "Delete failed", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@NotesActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddSourcesDialog() {
        val savedIdFile = File(currentDirectory, ".notebook_id")
        if (!savedIdFile.exists()) {
            Toast.makeText(this, "No notebook linked to this folder", Toast.LENGTH_SHORT).show()
            return
        }
        val notebookId = savedIdFile.readText().trim()

        val filesInDir = currentDirectory?.listFiles()?.filter { !it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        if (filesInDir.isEmpty()) {
            Toast.makeText(this, "No files in folder to add", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = filesInDir.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(fileNames.size) { false }

        AlertDialog.Builder(this)
            .setTitle("Add Sources")
            .setMultiChoiceItems(fileNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Add Selected") { _, _ ->
                val selectedFilesList = mutableListOf<File>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) selectedFilesList.add(filesInDir[i])
                }
                uploadFilesToNotebook(notebookId, selectedFilesList)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadFilesToNotebook(notebookId: String, files: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val py = Python.getInstance()
            val module = py.getModule("bridge")
            withContext(Dispatchers.Main) { Toast.makeText(this@NotesActivity, "Uploading...", Toast.LENGTH_SHORT).show() }

            var success = 0
            files.forEach { file ->
                try {
                    if (file.name.endsWith(".url")) {
                        module.callAttr("add_url", notebookId, file.readText().trim())
                    } else {
                        module.callAttr("upload_file", notebookId, file.absolutePath)
                    }
                    success++
                } catch (e: Exception) { Log.e("NLM", "Upload failed for ${file.name}", e) }
            }
            withContext(Dispatchers.Main) { Toast.makeText(this@NotesActivity, "Added $success sources", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showRemoveSourcesDialog() {
        val savedIdFile = File(currentDirectory, ".notebook_id")
        if (!savedIdFile.exists()) {
            Toast.makeText(this, "No notebook linked to this folder", Toast.LENGTH_SHORT).show()
            return
        }
        val notebookId = savedIdFile.readText().trim()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                val sourcesJson = module.callAttr("list_sources", notebookId).toString()
                val sourcesArray = JSONArray(sourcesJson)

                val names = mutableListOf<String>()
                val ids = mutableListOf<String>()
                for (i in 0 until sourcesArray.length()) {
                    val obj = sourcesArray.getJSONObject(i)
                    names.add(obj.getString("title"))
                    ids.add(obj.getString("id"))
                }

                withContext(Dispatchers.Main) {
                    if (names.isEmpty()) {
                        Toast.makeText(this@NotesActivity, "No sources found", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    AlertDialog.Builder(this@NotesActivity)
                        .setTitle("Remove Sources")
                        .setItems(names.toTypedArray()) { _, which ->
                            deleteSourceFromNotebook(notebookId, ids[which], names[which])
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e("NLM", "List failed", e)
            }
        }
    }

    private fun deleteSourceFromNotebook(notebookId: String, sourceId: String, title: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                module.callAttr("delete_source", notebookId, sourceId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "Removed: $title", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("NLM", "Remove failed", e)
            }
        }
    }

    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) selectedFiles.remove(file) else selectedFiles.add(file)
        updateEditTitle()
        explorerAdapter.notifyDataSetChanged()
    }

    private fun confirmDeleteSelectedFiles() {
        if (selectedFiles.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${selectedFiles.size} items?")
            .setMessage("Are you sure you want to delete these files and folders?")
            .setPositiveButton("Delete") { _, _ ->
                selectedFiles
                    .filter(::isWithinRootDirectory)
                    .forEach { it.deleteRecursively() }
                toggleEditMode(false)
                browseFiles(currentDirectory)
                Toast.makeText(this, "Deleted items", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isWithinRootDirectory(file: File): Boolean {
        val root = rootDirectory ?: return false
        val canonicalRoot = try {
            root.canonicalFile
        } catch (_: Exception) {
            return false
        }
        val canonicalFile = try {
            file.canonicalFile
        } catch (_: Exception) {
            return false
        }
        return canonicalFile == canonicalRoot ||
            canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
    }

    private fun shareSelectedFiles() {
        if (selectedFiles.isEmpty()) return
        try {
            val uris = ArrayList<Uri>()
            selectedFiles.forEach { file ->
                if (!file.isDirectory) {
                    uris.add(FileProvider.getUriForFile(this, "${packageName}.provider", file))
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(this, "No files selected to share", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = "*/*"
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) else putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${uris.size} Files"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFiles() {
        val targetDir = currentDirectory ?: rootDirectory!!
        var successCount = 0
        cutFiles?.forEach { file ->
            val dest = File(targetDir, file.name)
            if (file.renameTo(dest)) successCount++
        }

        if (successCount > 0) {
            Toast.makeText(this, "Moved $successCount items", Toast.LENGTH_SHORT).show()
            browseFiles(currentDirectory)
        }
        cutFiles = null
        binding.btnPasteFile.isVisible = false
    }

    private fun showRenameDialog(file: File) {
        val editText = EditText(this).apply {
            setText(file.name)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename ${if (file.isDirectory) "Folder" else "File"}")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val dest = File(file.parentFile, newName)
                    if (file.renameTo(dest)) {
                        Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show()
                        browseFiles(currentDirectory)
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePendingFiles() {
        if (pendingUris.isEmpty()) return
        var successCount = 0
        pendingUris.forEach { if (saveUriToFolder(it, currentDirectory ?: rootDirectory!!)) successCount++ }

        if (successCount > 0) {
            val folderName = when(explorerType) {
                TYPE_UPDATE -> "Updates"
                else -> "Notes"
            }
            Toast.makeText(this, "Saved $successCount file(s) to $folderName", Toast.LENGTH_SHORT).show()
            browseFiles(currentDirectory)
            pendingUris.clear()
            binding.btnPasteFile.isVisible = false
        } else {
            Toast.makeText(this, "Failed to save files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveUriToFolder(uri: Uri, folder: File): Boolean {
        return try {
            val fileName = getFileName(uri) ?: "shared_file_${System.currentTimeMillis()}"
            val destFile = File(folder, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = it.getString(index)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cutIndex = name?.lastIndexOf('/') ?: -1
            if (cutIndex != -1) name = name?.substring(cutIndex + 1)
        }
        return name
    }

    private fun browseFiles(directory: File?, preservePreviewState: Boolean = false) {
        val target = directory ?: rootDirectory!!
        currentDirectory = target

        if (!isEditMode) {
                binding.toolbar.title = if (target == rootDirectory) {
                    when(explorerType) {
                        TYPE_UPDATE -> "Updates"
                        else -> intent.getStringExtra(EXTRA_NOTES_SUBJECT) ?: "Notes"
                    }
                } else target.name
            updateGitButtonsVisibility()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val files = target.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?.toMutableList() ?: mutableListOf()

            if (explorerType == TYPE_NOTES) files.add(0, File(target, ".__settings__"))

            withContext(Dispatchers.Main) {
                explorerAdapter.submitList(files)
                if (!preservePreviewState) {
                    binding.rvFiles.isVisible = true
                    binding.scrollFileContent.isVisible = false
                    binding.codeEditor.isVisible = false
                    binding.pdfView.isVisible = false
                    binding.ivFilePreview.isVisible = false
                    binding.btnShareFile.isVisible = false
                    binding.btnSaveFile.isVisible = false
                    selectedFile = null
                    textEditorFile = null
                    editorDirty = false
                }
                binding.btnPasteFile.isVisible = (pendingUris.isNotEmpty() || cutFiles != null) && !isEditMode
                updatePythonRunVisibility()
                if (!initialTargetOpened && target == rootDirectory) {
                    openInitialTargetIfRequested()
                }
            }
        }
    }

    private fun openInitialTargetIfRequested() {
        val relativePath = initialRelativePath ?: return
        val root = rootDirectory ?: return
        initialTargetOpened = true

        val resolved = File(root, relativePath)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = try {
            resolved.canonicalFile
        } catch (_: Exception) {
            return
        }

        if (canonicalTarget != canonicalRoot &&
            !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            return
        }

        if (!canonicalTarget.exists()) {
            return
        }

        if (canonicalTarget.isDirectory) {
            browseFiles(canonicalTarget)
            return
        }

        currentDirectory = canonicalTarget.parentFile ?: canonicalRoot
        openFile(canonicalTarget)
    }

    private fun updatePythonRunVisibility() {
        binding.btnRunPython.isVisible = !isEditMode && selectedFile?.extension?.lowercase() == "py"
    }

    private fun setupEditor() {
        binding.codeEditor.apply {
            typefaceText = Typeface.MONOSPACE
            subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                if (!isEditorContentLoading && textEditorFile != null) {
                    editorDirty = true
                    updateEditorActions()
                }
            }
        }
    }

    private fun showPythonRunDialog(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ensurePythonStarted()
                val bridge = Python.getInstance().getModule("bridge")
                val raw = bridge.callAttr("inspect_script_interface", file.absolutePath).toString()
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    val error = json.optString("error", "Could not inspect script inputs.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@NotesActivity, error, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val inputs = parsePythonInputSpecs(json.optJSONArray("inputs") ?: JSONArray())
                withContext(Dispatchers.Main) {
                    showPythonRunnerForm(
                        file = file,
                        mode = json.optString("mode", "input"),
                        specs = inputs
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "Runner error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parsePythonInputSpecs(array: JSONArray): List<PythonInputSpec> {
        val specs = mutableListOf<PythonInputSpec>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val choicesArray = obj.optJSONArray("choices") ?: JSONArray()
            val choices = mutableListOf<String>()
            for (j in 0 until choicesArray.length()) {
                choices += choicesArray.optString(j)
            }
            specs += PythonInputSpec(
                id = obj.optString("id", "input_${i + 1}"),
                mode = obj.optString("mode", "input"),
                kind = obj.optString("kind", "text"),
                label = obj.optString("label", "Input ${i + 1}"),
                choices = choices,
                required = obj.optBoolean("required", false),
                defaultValue = obj.optString("default").takeIf { obj.has("default") && it != "null" },
                helpText = obj.optString("help").takeIf { obj.has("help") && it.isNotBlank() },
                argNames = buildList {
                    val names = obj.optJSONArray("arg_names") ?: JSONArray()
                    for (j in 0 until names.length()) {
                        add(names.optString(j))
                    }
                },
                isOptional = obj.optBoolean("is_optional", false),
                action = obj.optString("action").takeIf { obj.has("action") && it.isNotBlank() },
                multiple = obj.optBoolean("multiple", false),
                typeName = obj.optString("type_name").takeIf { obj.has("type_name") && it.isNotBlank() }
            )
        }
        return specs
    }

    private fun showPythonRunnerForm(file: File, mode: String, specs: List<PythonInputSpec>) {
        pythonFieldViews.clear()
        pendingPythonPickerFieldId = null
        pythonRunnerMode = mode

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }

        val intro = TextView(this).apply {
            text = if (specs.isEmpty()) {
                "No script parameters were detected. Run the script now?"
            } else if (mode == "argparse") {
                "Configure the script arguments, then run."
            } else {
                "Fill the detected script inputs, then run."
            }
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        container.addView(intro)

        specs.forEach { spec ->
            val label = TextView(this).apply {
                text = spec.label
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(0, 28, 0, 10)
            }
            container.addView(label)

            spec.helpText?.takeIf { it.isNotBlank() }?.let { help ->
                container.addView(TextView(this).apply {
                    text = help
                    setTextColor(Color.parseColor("#B3FFFFFF"))
                    textSize = 12f
                    setPadding(0, 0, 0, 8)
                })
            }

            when (spec.kind) {
                "file" -> {
                    val value = TextView(this).apply {
                        text = spec.defaultValue ?: "No file selected"
                        setTextColor(Color.parseColor("#B3FFFFFF"))
                        setPadding(0, 0, 0, 12)
                        tag = spec.defaultValue
                    }
                    val button = android.widget.Button(this).apply {
                        text = "Pick File"
                        setOnClickListener {
                            pendingPythonPickerFieldId = spec.id
                            pythonFilePickerLauncher.launch(arrayOf("*/*"))
                        }
                    }
                    pythonFieldViews[spec.id] = PythonFieldViewState(spec = spec, fileValue = value)
                    container.addView(value)
                    container.addView(button)
                }
                "bool" -> {
                    val checkBox = CheckBox(this).apply {
                        text = spec.helpText ?: spec.label
                        setTextColor(Color.WHITE)
                        isChecked = when (spec.action) {
                            "store_true" -> spec.defaultValue.equals("true", ignoreCase = true)
                            "store_false" -> false
                            else -> spec.defaultValue.equals("true", ignoreCase = true)
                        }
                    }
                    pythonFieldViews[spec.id] = PythonFieldViewState(spec = spec, boolInput = checkBox)
                    container.addView(checkBox)
                }
                "choice" -> {
                    val group = RadioGroup(this).apply {
                        orientation = RadioGroup.VERTICAL
                    }
                    val defaultChoice = spec.defaultValue
                    spec.choices.forEachIndexed { index, choice ->
                        val radio = RadioButton(this).apply {
                            id = View.generateViewId()
                            text = choice
                            setTextColor(Color.WHITE)
                            if (choice == defaultChoice || (defaultChoice == null && index == 0)) {
                                isChecked = true
                            }
                        }
                        group.addView(radio)
                    }
                    pythonFieldViews[spec.id] = PythonFieldViewState(spec = spec, choiceGroup = group)
                    container.addView(group)
                }
                else -> {
                    val input = EditText(this).apply {
                        hint = spec.label
                        setTextColor(Color.WHITE)
                        setHintTextColor(Color.parseColor("#80FFFFFF"))
                        setSingleLine(!spec.multiple)
                        setText(spec.defaultValue.orEmpty())
                        inputType = if (spec.typeName == "int" || spec.typeName == "float" || spec.kind == "number") {
                            InputType.TYPE_CLASS_NUMBER or
                                (if (spec.typeName == "float") InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                        } else {
                            InputType.TYPE_CLASS_TEXT
                        }
                    }
                    pythonFieldViews[spec.id] = PythonFieldViewState(spec = spec, textInput = input)
                    container.addView(input)
                }
            }
        }

        val scroll = ScrollView(this).apply {
            addView(container)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Run ${file.name}")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val inputs = collectPythonRunnerPayload() ?: return@setOnClickListener
                dialog.dismiss()
                when (pythonRunnerMode) {
                    "argparse" -> runPythonScriptWithArgs(file, inputs)
                    else -> runPythonScriptWithInputs(file, inputs)
                }
            }
        }
        dialog.setOnDismissListener {
            pythonFieldViews.clear()
            pendingPythonPickerFieldId = null
            pythonRunnerMode = "input"
        }
        dialog.show()
    }

    private fun collectPythonRunnerPayload(): List<String>? {
        val values = mutableListOf<String>()
        pythonFieldViews.values.forEach { field ->
            if (pythonRunnerMode == "argparse") {
                appendArgparseValues(field, values) ?: return null
                return@forEach
            }
            when (field.spec.kind) {
                "file" -> {
                    val path = field.fileValue?.tag as? String
                    if (path.isNullOrBlank() && field.spec.required) {
                        Toast.makeText(this, "Pick a file for ${field.spec.label}.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    if (!path.isNullOrBlank()) {
                        values += path
                    }
                }
                "choice" -> {
                    val group = field.choiceGroup
                    val checkedId = group?.checkedRadioButtonId ?: View.NO_ID
                    if (checkedId == View.NO_ID) {
                        Toast.makeText(this, "Choose an option for ${field.spec.label}.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    val radio = group?.findViewById<RadioButton>(checkedId)
                    if (radio == null) {
                        Toast.makeText(this, "Choose an option for ${field.spec.label}.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    values += radio.text.toString()
                }
                else -> {
                    values += field.textInput?.text?.toString().orEmpty()
                }
            }
        }
        return values
    }

    private fun appendArgparseValues(field: PythonFieldViewState, values: MutableList<String>): Unit? {
        val spec = field.spec
        val names = spec.argNames
        val primaryFlag = names.firstOrNull { it.startsWith("--") } ?: names.firstOrNull()

        when (spec.kind) {
            "bool" -> {
                val checked = field.boolInput?.isChecked == true
                if (checked && primaryFlag != null) {
                    values += primaryFlag
                }
            }
            "file" -> {
                val path = field.fileValue?.tag as? String
                if (path.isNullOrBlank()) {
                    if (spec.required) {
                        Toast.makeText(this, "Pick a file for ${spec.label}.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    return Unit
                }
                if (spec.isOptional && primaryFlag != null) {
                    values += primaryFlag
                }
                values += path
            }
            "choice" -> {
                val group = field.choiceGroup
                val checkedId = group?.checkedRadioButtonId ?: View.NO_ID
                if (checkedId == View.NO_ID) {
                    if (spec.required) {
                        Toast.makeText(this, "Choose an option for ${spec.label}.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    return Unit
                }
                val radio = group?.findViewById<RadioButton>(checkedId)
                if (radio == null) {
                    if (spec.required) {
                        Toast.makeText(this, "Choose an option for ${spec.label}.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    return Unit
                }
                val value = radio.text.toString()
                if (spec.isOptional && primaryFlag != null) {
                    values += primaryFlag
                }
                values += value
            }
            else -> {
                val raw = field.textInput?.text?.toString().orEmpty().trim()
                if (raw.isBlank()) {
                    if (spec.required) {
                        Toast.makeText(this, "${spec.label} is required.", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    return Unit
                }
                if (spec.isOptional && primaryFlag != null) {
                    values += primaryFlag
                }
                if (spec.multiple) {
                    raw.split('\n')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { values += it }
                } else {
                    values += raw
                }
            }
        }
        return Unit
    }

    private fun handlePythonFilePicked(uri: Uri?) {
        val fieldId = pendingPythonPickerFieldId ?: return
        pendingPythonPickerFieldId = null
        if (uri == null) {
            return
        }

        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            val displayName = (resolveDocumentName(uri) ?: "input_file")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetDir = File(cacheDir, "python_inputs").apply { mkdirs() }
            val targetFile = File(targetDir, "${System.currentTimeMillis()}_${displayName}")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open selected file." }
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            pythonFieldViews[fieldId]?.fileValue?.apply {
                text = displayName
                tag = targetFile.absolutePath
                setTextColor(Color.WHITE)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "File picker failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resolveDocumentName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun runPythonScriptWithInputs(file: File, inputs: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ensurePythonStarted()
                val bridge = Python.getInstance().getModule("bridge")
                val resultRaw = bridge.callAttr(
                    "run_script_with_inputs",
                    file.absolutePath,
                    JSONArray(inputs).toString()
                ).toString()
                val result = JSONObject(resultRaw)
                withContext(Dispatchers.Main) {
                    showPythonOutputDialog(
                        file = file,
                        ok = result.optBoolean("ok", false),
                        stdout = result.optString("stdout"),
                        stderr = result.optString("stderr")
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "Run failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runPythonScriptWithArgs(file: File, args: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ensurePythonStarted()
                val bridge = Python.getInstance().getModule("bridge")
                val resultRaw = bridge.callAttr(
                    "run_script_with_args",
                    file.absolutePath,
                    JSONArray(args).toString()
                ).toString()
                val result = JSONObject(resultRaw)
                withContext(Dispatchers.Main) {
                    showPythonOutputDialog(
                        file = file,
                        ok = result.optBoolean("ok", false),
                        stdout = result.optString("stdout"),
                        stderr = result.optString("stderr")
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotesActivity, "Run failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPythonOutputDialog(file: File, ok: Boolean, stdout: String, stderr: String) {
        val outputView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(40, 24, 40, 24)
            text = buildString {
                appendLine(if (ok) "Execution completed." else "Execution failed.")
                appendLine()
                if (stdout.isNotBlank()) {
                    appendLine("STDOUT")
                    appendLine(stdout.trimEnd())
                }
                if (stderr.isNotBlank()) {
                    if (stdout.isNotBlank()) {
                        appendLine()
                    }
                    appendLine("STDERR")
                    appendLine(stderr.trimEnd())
                }
                if (stdout.isBlank() && stderr.isBlank()) {
                    append("No output.")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(ScrollView(this).apply { addView(outputView) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openFile(file: File) {
        if (file.isDirectory) {
            browseFiles(file)
        } else {
            if (file.name.endsWith(".url")) {
                openWebView(file)
                return
            }
            if (file.name.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }) {
                renderHtmlFile(file)
                return
            }
            if (explorerType == TYPE_UPDATE && file.name.endsWith(".apk")) {
                installApk(file)
                return
            }

            selectedFile = file
            binding.rvFiles.isVisible = false
            binding.scrollFileContent.isVisible = false
            binding.codeEditor.isVisible = false
            binding.pdfView.isVisible = false
            binding.ivFilePreview.isVisible = false
            binding.toolbar.title = file.name
            binding.btnShareFile.isVisible = true
            binding.btnPasteFile.isVisible = false
            binding.btnPush.isVisible = false
            binding.btnPull.isVisible = false
            textEditorFile = null
            editorDirty = false
            updateEditorActions()
            updatePythonRunVisibility()

            val fileName = file.name.lowercase()
            when {
                fileName.endsWith(".pdf") -> {
                    binding.pdfView.isVisible = true
                    binding.pdfView.fromFile(file).load()
                }
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".png") || fileName.endsWith(".webp") -> {
                    binding.ivFilePreview.isVisible = true
                    Glide.with(this).load(file).into(binding.ivFilePreview)
                }
                fileName.endsWith(".docx") -> {
                    showTextPreview("Loading DOCX...")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val text = try {
                            XWPFDocument(file.inputStream()).use { doc ->
                                doc.paragraphs.joinToString("\n") { it.text }
                            }
                        } catch (e: Exception) { "Error: ${e.message}" }
                        withContext(Dispatchers.Main) { showTextPreview(text) }
                    }
                }
                fileName.endsWith(".pptx") -> {
                    showTextPreview("Loading PPTX...")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val text = try {
                            XMLSlideShow(file.inputStream()).use { ppt ->
                                ppt.slides.joinToString("\n---\n") { slide ->
                                    slide.shapes.filterIsInstance<XSLFTextShape>().joinToString("\n") { it.text }
                                }
                            }
                        } catch (e: Exception) { "Error: ${e.message}" }
                        withContext(Dispatchers.Main) { showTextPreview(text) }
                    }
                }
                fileName.endsWith(".ppt") -> {
                    showTextPreview("Loading PPT...")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val text = try {
                            HSLFSlideShow(file.inputStream()).use { ppt ->
                                ppt.slides.joinToString("\n---\n") { slide ->
                                    slide.shapes.filterIsInstance<HSLFShape>().joinToString("\n") {
                                        if (it is HSLFTextShape) it.text else ""
                                    }
                                }
                            }
                        } catch (e: Exception) { "Error: ${e.message}" }
                        withContext(Dispatchers.Main) { showTextPreview(text) }
                    }
                }
                else -> {
                    openTextEditor(file)
                }
            }
        }
    }

    private fun showTextPreview(text: String) {
        binding.scrollFileContent.isVisible = true
        binding.codeEditor.isVisible = false
        binding.tvFileContent.text = text
        textEditorFile = null
        editorDirty = false
        updateEditorActions()
    }

    private fun openTextEditor(file: File) {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            showTextPreview("Error: ${e.message}")
            return
        }

        isEditorContentLoading = true
        binding.codeEditor.isVisible = true
        binding.scrollFileContent.isVisible = false
        binding.codeEditor.setText(text)
        textEditorFile = file
        editorDirty = false
        isEditorContentLoading = false
        updateEditorActions()
    }

    private fun saveEditedFile(): Boolean {
        val file = textEditorFile ?: return false
        return try {
            file.writeText(binding.codeEditor.text.toString())
            editorDirty = false
            updateEditorActions()
            Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun renderHtmlFile(file: File) {
        val webBinding = ActivityErpWebviewBinding.inflate(layoutInflater, binding.webViewContainer, false)
        webBinding.btnBack.visibility = View.VISIBLE
        webBinding.btnBack.setOnClickListener {
            binding.webViewContainer.removeView(webBinding.root)
            WebViewWindowManager.onViewRemoved(webBinding.root)
            if (activeWebView == webBinding.webView) activeWebView = null
        }
        activeWebView = webBinding.webView
        webBinding.toolbar.visibility = View.GONE
        webBinding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        webBinding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                webBinding.progressBar.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                webBinding.progressBar.visibility = View.GONE
            }
        }
        webBinding.webView.loadUrl("file://" + file.absolutePath)
        WebViewWindowManager.addWebView(binding.webViewContainer, webBinding.root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebView(file: File) {
        val url = try { file.readText() } catch (e: Exception) { "" }
        if (url.isEmpty()) return
        val webBinding = ActivityErpWebviewBinding.inflate(layoutInflater, binding.webViewContainer, false)
        webBinding.btnBack.visibility = View.VISIBLE
        webBinding.btnBack.setOnClickListener {
            binding.webViewContainer.removeView(webBinding.root)
            WebViewWindowManager.onViewRemoved(webBinding.root)
            if (activeWebView == webBinding.webView) activeWebView = null
        }
        activeWebView = webBinding.webView
        webBinding.toolbar.visibility = View.GONE
        webBinding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        webBinding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { webBinding.progressBar.visibility = View.VISIBLE }
            override fun onPageFinished(view: WebView?, url: String?) {
                webBinding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }
        }
        webBinding.webView.loadUrl(url)
        WebViewWindowManager.addWebView(binding.webViewContainer, webBinding.root)
    }

    private fun installApk(file: File) {
        if (explorerType == TYPE_UPDATE) {
            startActivity(Intent(this, UpdateInstallActivity::class.java)
                .putExtra(UpdateInstallActivity.EXTRA_APK_PATH, file.absolutePath))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun closeFile() {
        selectedFile = null
        textEditorFile = null
        editorDirty = false
        updateEditorActions()
        browseFiles(currentDirectory)
    }

    private fun navigateUp() {
        val parent = currentDirectory?.parentFile
        if (parent != null && parent.absolutePath.startsWith(rootDirectory!!.absolutePath)) browseFiles(parent) else browseFiles(rootDirectory)
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = when {
                    file.name.endsWith(".apk") -> "application/vnd.android.package-archive"
                    file.name.endsWith(".pdf") -> "application/pdf"
                    file.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") } -> "image/*"
                    else -> "text/plain"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share File"))
        } catch (e: Exception) { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onBackPressed() {
        if (binding.chatContainer.visibility == View.VISIBLE) binding.chatContainer.visibility = View.GONE
        else if (isEditMode) toggleEditMode(false)
        else if (selectedFile != null) attemptCloseSelectedFile()
        else if (currentDirectory != rootDirectory) navigateUp()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.codeEditor.release()
    }

    inner class FileExplorerAdapter(private val onFileClick: (File) -> Unit, private val onRenameClick: (File) -> Unit) : RecyclerView.Adapter<FileExplorerAdapter.VH>() {
        private var items = listOf<File>()
        private val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        fun submitList(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(ItemFileExplorerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            if (file.name == ".__settings__") {
                holder.binding.tvFileName.text = "Settings"
                holder.binding.tvFileInfo.text = "Notebook Configuration"
                holder.binding.ivFileIcon.setImageResource(R.drawable.ic_back)
                holder.binding.ivFileIcon.rotation = 90f
                holder.binding.cbSelect.isVisible = false
                holder.binding.root.setOnClickListener { onFileClick(file) }
                return
            }
            holder.binding.tvFileName.text = file.name.removeSuffix(".url")
            val info = if (file.isDirectory) "Folder" else if (file.name.endsWith(".url")) "URL Shortcut" else {
                val size = if (file.length() > 1024 * 1024) "${file.length() / (1024 * 1024)} MB" else "${file.length() / 1024} KB"
                "$size • ${sdf.format(Date(file.lastModified()))}"
            }
            holder.binding.tvFileInfo.text = info
            holder.binding.ivFileIcon.setImageResource(if (file.isDirectory) R.drawable.ic_back else if (file.name.endsWith(".url")) R.drawable.ic_gpt else R.drawable.ic_file)
            holder.binding.ivFileIcon.rotation = if (file.isDirectory) 180f else 0f
            holder.binding.cbSelect.isVisible = isEditMode
            holder.binding.cbSelect.isChecked = selectedFiles.contains(file)
            holder.binding.cbSelect.setOnClickListener { onFileClick(file) }
            holder.binding.root.setOnClickListener { onFileClick(file) }
            holder.binding.ivFileIcon.setOnClickListener { if (isEditMode) onFileClick(file) else onRenameClick(file) }
            holder.binding.cardView.setCardBackgroundColor(if (selectedFiles.contains(file)) Color.parseColor("#40FFFFFF") else Color.parseColor("#1AFFFFFF"))
        }
        override fun getItemCount() = items.size
        inner class VH(val binding: ItemFileExplorerBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
