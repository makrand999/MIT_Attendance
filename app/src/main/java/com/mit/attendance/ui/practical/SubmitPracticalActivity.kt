package com.mit.attendance.ui.practical

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.data.PracticalRepository
import com.mit.attendance.model.Practical
import com.mit.attendance.model.PracticalEditorState
import com.mit.attendance.model.RemoteEditorSource
import com.mit.attendance.ui.theme.MedKitMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubmitPracticalActivity : AppCompatActivity() {
    companion object {
        private const val MENU_CLEAR_DRAFT = 1
        private const val MENU_EDIT_THEORY_PROMPT = 2
        private const val MENU_EDIT_CONCLUSION_PROMPT = 3
        private const val MENU_RESET_AI_PROMPTS = 4
    }

    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(application, UserPreferences(this))
    }

    private lateinit var tvAim: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvExpectedOutput: TextView
    private lateinit var cardExpectedOutput: View
    private lateinit var tvSyncHeadline: TextView
    private lateinit var tvLocalDraftMeta: TextView
    private lateinit var tvServerSyncMeta: TextView
    private lateinit var tvServerVerifyMeta: TextView
    private lateinit var tvSyncError: TextView
    private lateinit var btnRetrySync: Button
    private lateinit var btnCopyAll: ImageButton
    private lateinit var btnPasteTheory: Button
    private lateinit var btnPasteConclusion: Button
    private lateinit var btnChoosePdf: Button
    private lateinit var btnRemovePdf: Button
    private lateinit var btnPreviewPdf: Button
    private lateinit var btnSaveServerDraft: Button
    private lateinit var btnLoadServerCopy: Button
    private lateinit var etTheory: EditText
    private lateinit var etCode: EditText
    private lateinit var etOutput: EditText
    private lateinit var etError: EditText
    private lateinit var etConclusion: EditText
    private lateinit var spinnerLanguage: Spinner
    private lateinit var btnAiTheory: Button
    private lateinit var btnAiConclusion: Button
    private lateinit var btnSubmit: Button
    private lateinit var progressTheory: ProgressBar
    private lateinit var progressConclusion: ProgressBar
    private lateinit var progressSubmit: ProgressBar
    private lateinit var tvSubmitStatus: TextView
    private lateinit var tvAttachmentName: TextView
    private lateinit var tvRemoteAttachment: TextView

    private var subjectId = 0
    private var subjectName = ""
    private var practicalId = 0
    private var practicalNumber = 0
    private var practicalAim = ""
    private var practicalDesc = ""
    private var expectedOutput = ""
    private var isSubmitted = false
    private var submissionId: Int? = null
    private var selectedPdfUri: String? = null
    private var selectedPdfName: String? = null
    private var remoteAttachmentPath: String? = null
    private var remoteAttachmentName: String? = null
    private var removePdfOnSubmit = false
    private var saveJob: Job? = null
    private var pendingAction = PendingAction.SUBMIT
    private var manualRemoteLoad = false
    private var currentPracticalRecord: Practical? = null
    private var theoryPromptTemplate = ""
    private var conclusionPromptTemplate = ""
    private var pendingLoginRecovery: PendingAction? = null

    private val languageOptions = listOf("python", "java", "c", "cpp", "javascript", "kotlin", "other")

    private val chatGptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val type = result.data?.getStringExtra(ChatGPTWebViewActivity.EXTRA_FIELD_TYPE)
            val returnedText = result.data?.getStringExtra(ChatGPTWebViewActivity.EXTRA_PASTED_TEXT)

            when (type) {
                ChatGPTWebViewActivity.FIELD_THEORY -> {
                    if (returnedText != null) {
                        etTheory.setText(returnedText)
                        Toast.makeText(this, "Pasted into Theory", Toast.LENGTH_SHORT).show()
                    } else {
                        pasteToEditText(etTheory, "Theory")
                    }
                }

                ChatGPTWebViewActivity.FIELD_CONCLUSION -> {
                    if (returnedText != null) {
                        etConclusion.setText(returnedText)
                        Toast.makeText(this, "Pasted into Conclusion", Toast.LENGTH_SHORT).show()
                    } else {
                        pasteToEditText(etConclusion, "Conclusion")
                    }
                }
            }
        }
    }

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedPdfUri = uri.toString()
            selectedPdfName = resolveDisplayName(uri)
            removePdfOnSubmit = false
            renderAttachmentState()
            saveCurrentDraft()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_submit_practical)

        subjectId = intent.getIntExtra(PracticalActivity.EXTRA_SUBJECT_ID, 0)
        subjectName = intent.getStringExtra(PracticalActivity.EXTRA_SUBJECT_NAME) ?: ""
        practicalId = intent.getIntExtra("practical_id", 0)
        practicalNumber = intent.getIntExtra("practical_number", 0)
        practicalAim = intent.getStringExtra("practical_title") ?: ""
        practicalDesc = intent.getStringExtra("practical_desc") ?: ""
        expectedOutput = intent.getStringExtra("expected_output") ?: ""
        isSubmitted = intent.getBooleanExtra("is_submitted", false)
        submissionId = intent.getIntExtra("existing_submission_id", 0).takeIf { it > 0 }
        remoteAttachmentPath = intent.getStringExtra("existing_pdf_name")
        remoteAttachmentName = remoteAttachmentPath?.substringAfterLast('/')?.ifBlank { remoteAttachmentPath }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = "Practical $practicalNumber"
            setDisplayHomeAsUpEnabled(true)
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).navigationIcon
            ?.setTint(getColor(R.color.medkit_line))

        bindViews()
        setupLanguageSpinner()
        populateStaticContent()
        populateExistingContent()
        setupActions()
        observeViewModel()
        loadPromptTemplates()
        MedKitMotion.playEntrance(
            findViewById(R.id.toolbar),
            findViewById(R.id.cardAimDesc),
            findViewById(R.id.cardExpectedOutput),
            findViewById(R.id.cardSyncStatus)
        )
        MedKitMotion.applyFloat(btnSubmit, distanceDp = 3f, durationMs = 2200L)

        viewModel.loadDraft(practicalId)
        viewModel.loadPracticalRecord(practicalId)
        if (viewModel.loginState.value !is UiState.Success) {
            viewModel.init(loadSubjects = false)
        } else {
            viewModel.loadRemoteEditorState(practicalId)
        }
    }

    private fun bindViews() {
        tvAim = findViewById(R.id.tvAim)
        tvDesc = findViewById(R.id.tvDesc)
        tvExpectedOutput = findViewById(R.id.tvExpectedOutput)
        cardExpectedOutput = findViewById(R.id.cardExpectedOutput)
        tvSyncHeadline = findViewById(R.id.tvSyncHeadline)
        tvLocalDraftMeta = findViewById(R.id.tvLocalDraftMeta)
        tvServerSyncMeta = findViewById(R.id.tvServerSyncMeta)
        tvServerVerifyMeta = findViewById(R.id.tvServerVerifyMeta)
        tvSyncError = findViewById(R.id.tvSyncError)
        btnRetrySync = findViewById(R.id.btnRetrySync)
        btnCopyAll = findViewById(R.id.btnCopyAll)
        btnPasteTheory = findViewById(R.id.btnPasteTheory)
        btnPasteConclusion = findViewById(R.id.btnPasteConclusion)
        btnChoosePdf = findViewById(R.id.btnChoosePdf)
        btnRemovePdf = findViewById(R.id.btnRemovePdf)
        btnPreviewPdf = findViewById(R.id.btnPreviewPdf)
        btnSaveServerDraft = findViewById(R.id.btnSaveServerDraft)
        btnLoadServerCopy = findViewById(R.id.btnLoadServerCopy)
        etTheory = findViewById(R.id.etTheory)
        etCode = findViewById(R.id.etCode)
        etOutput = findViewById(R.id.etOutput)
        etError = findViewById(R.id.etError)
        etConclusion = findViewById(R.id.etConclusion)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        btnAiTheory = findViewById(R.id.btnAiTheory)
        btnAiConclusion = findViewById(R.id.btnAiConclusion)
        btnSubmit = findViewById(R.id.btnSubmit)
        progressTheory = findViewById(R.id.progressTheory)
        progressConclusion = findViewById(R.id.progressConclusion)
        progressSubmit = findViewById(R.id.progressSubmit)
        tvSubmitStatus = findViewById(R.id.tvSubmitStatus)
        tvAttachmentName = findViewById(R.id.tvAttachmentName)
        tvRemoteAttachment = findViewById(R.id.tvRemoteAttachment)
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter
        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveCurrentDraft()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun populateStaticContent() {
        tvAim.text = practicalAim
        if (practicalDesc.isBlank()) {
            tvDesc.visibility = View.GONE
        } else {
            tvDesc.visibility = View.VISIBLE
            tvDesc.text = practicalDesc
        }

        if (expectedOutput.isBlank()) {
            cardExpectedOutput.visibility = View.GONE
        } else {
            cardExpectedOutput.visibility = View.VISIBLE
            tvExpectedOutput.text = expectedOutput
        }

        renderSubmitButton()
        renderAttachmentState()
    }

    private fun populateExistingContent() {
        val existingTheory = intent.getStringExtra("existing_theory").orEmpty()
        val existingCode = intent.getStringExtra("existing_code").orEmpty()
        val existingOutput = intent.getStringExtra("existing_output").orEmpty()
        val existingError = intent.getStringExtra("existing_error").orEmpty()
        val existingConclusion = intent.getStringExtra("existing_concl").orEmpty()
        val existingLanguage = intent.getStringExtra("existing_language").orEmpty()

        if (existingTheory.isNotBlank()) etTheory.setText(existingTheory)
        if (existingCode.isNotBlank()) etCode.setText(existingCode)
        if (existingOutput.isNotBlank()) etOutput.setText(existingOutput)
        if (existingError.isNotBlank()) etError.setText(existingError)
        if (existingConclusion.isNotBlank()) etConclusion.setText(existingConclusion)
        if (existingLanguage.isNotBlank()) setLanguage(existingLanguage)
    }

    private fun setupActions() {
        btnCopyAll.setOnClickListener {
            val combinedText = StringBuilder().apply {
                if (subjectName.isNotBlank()) append("Subject: $subjectName\n")
                append("Aim: $practicalAim")
                if (practicalDesc.isNotBlank()) append("\n\nDescription: $practicalDesc")
                if (expectedOutput.isNotBlank()) append("\n\nExpected Output: $expectedOutput")
            }.toString()
            copyToClipboard("Practical Details", combinedText)
        }

        btnPasteTheory.setOnClickListener { pasteToEditText(etTheory, "Theory") }
        btnPasteConclusion.setOnClickListener { pasteToEditText(etConclusion, "Conclusion") }
        btnChoosePdf.setOnClickListener { pdfPickerLauncher.launch(arrayOf("application/pdf")) }
        btnRemovePdf.setOnClickListener {
            selectedPdfUri = null
            selectedPdfName = null
            removePdfOnSubmit = remoteAttachmentName != null
            renderAttachmentState()
            saveCurrentDraft()
        }
        btnPreviewPdf.setOnClickListener { previewPdf() }

        btnSaveServerDraft.setOnClickListener {
            saveCurrentDraftImmediately()
            saveCurrentServerDraft()
        }

        btnLoadServerCopy.setOnClickListener {
            manualRemoteLoad = true
            viewModel.loadRemoteEditorState(practicalId)
        }
        btnRetrySync.setOnClickListener {
            saveCurrentDraftImmediately()
            retryCurrentPendingSync()
        }

        btnAiTheory.setOnClickListener {
            showAiWifiNote()
            openChatGPT(buildPrompt(theoryPromptTemplate), ChatGPTWebViewActivity.FIELD_THEORY)
        }

        btnAiConclusion.setOnClickListener {
            showAiWifiNote()
            openChatGPT(buildPrompt(conclusionPromptTemplate), ChatGPTWebViewActivity.FIELD_CONCLUSION)
        }

        btnSubmit.setOnClickListener {
            saveCurrentDraftImmediately()
            submitCurrentPractical()
        }

        val watcher = { saveCurrentDraft() }
        etTheory.addTextChangedListener { watcher() }
        etCode.addTextChangedListener { watcher() }
        etOutput.addTextChangedListener { watcher() }
        etError.addTextChangedListener { watcher() }
        etConclusion.addTextChangedListener { watcher() }
    }

    private fun saveCurrentServerDraft() {
        pendingAction = PendingAction.SAVE_DRAFT
        viewModel.saveServerDraft(
            subjectId = subjectId,
            practicalId = practicalId,
            theory = etTheory.text.toString().trim(),
            conclusion = etConclusion.text.toString().trim(),
            language = selectedLanguage(),
            code = etCode.text.toString().trim(),
            output = etOutput.text.toString().trim(),
            error = effectiveErrorText(),
            pdfUri = selectedPdfUri
        )
    }

    private fun submitCurrentPractical() {
        val theory = etTheory.text.toString().trim()
        val conclusion = etConclusion.text.toString().trim()
        if (theory.isBlank()) {
            etTheory.error = "Theory is required"
            etTheory.requestFocus()
            return
        }
        if (conclusion.isBlank()) {
            etConclusion.error = "Conclusion is required"
            etConclusion.requestFocus()
            return
        }

        pendingAction = if (submissionId != null || isSubmitted) PendingAction.UPDATE else PendingAction.SUBMIT
        viewModel.submitPractical(
            subjectId = subjectId,
            practicalId = practicalId,
            submissionId = submissionId,
            theory = theory,
            conclusion = conclusion,
            language = selectedLanguage(),
            code = etCode.text.toString().trim(),
            output = etOutput.text.toString().trim(),
            error = effectiveErrorText(),
            pdfUri = selectedPdfUri,
            removePdf = removePdfOnSubmit
        )
    }

    private fun retryCurrentPendingSync() {
        val practical = currentPracticalRecord
        pendingAction = when (practical?.pendingSyncAction) {
            PracticalRepository.SYNC_ACTION_SUBMIT -> PendingAction.SUBMIT
            PracticalRepository.SYNC_ACTION_UPDATE -> PendingAction.UPDATE
            else -> PendingAction.SAVE_DRAFT
        }
        viewModel.retryPendingSync(
            subjectId = subjectId,
            practicalId = practicalId,
            submissionId = submissionId,
            theory = etTheory.text.toString().trim(),
            conclusion = etConclusion.text.toString().trim(),
            language = selectedLanguage(),
            code = etCode.text.toString().trim(),
            output = etOutput.text.toString().trim(),
            error = effectiveErrorText(),
            pdfUri = selectedPdfUri,
            removePdf = removePdfOnSubmit,
            pendingSyncAction = practical?.pendingSyncAction
        )
    }

    private fun retryServerAction(action: PendingAction) {
        when (action) {
            PendingAction.SAVE_DRAFT -> saveCurrentServerDraft()
            PendingAction.SUBMIT,
            PendingAction.UPDATE -> submitCurrentPractical()
        }
    }

    private fun saveCurrentDraftImmediately() {
        saveJob?.cancel()
        persistDraftToDisk()
    }

    private fun observeViewModel() {
        viewModel.loginState.observe(this) { state ->
            when (state) {
                is UiState.Success -> {
                    val recoveryAction = pendingLoginRecovery
                    pendingLoginRecovery = null
                    if (recoveryAction != null) {
                        retryServerAction(recoveryAction)
                    } else {
                        viewModel.loadRemoteEditorState(practicalId)
                    }
                }
                is UiState.Error -> handleEditorLoginError(state.message)
                else -> Unit
            }
        }

        viewModel.submitState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    progressSubmit.visibility = View.VISIBLE
                    btnSubmit.isEnabled = false
                    btnSaveServerDraft.isEnabled = false
                    btnLoadServerCopy.isEnabled = false
                    tvSubmitStatus.visibility = View.VISIBLE
                    tvSubmitStatus.setTextColor(resources.getColor(R.color.white_70, theme))
                    tvSubmitStatus.text = loadingMessageForAction()
                }

                is UiState.Success -> {
                    progressSubmit.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnSaveServerDraft.isEnabled = true
                    btnLoadServerCopy.isEnabled = true
                    tvSubmitStatus.visibility = View.VISIBLE
                    tvSubmitStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
                    tvSubmitStatus.text = successMessageForAction()
                    if (pendingAction != PendingAction.SAVE_DRAFT) {
                        isSubmitted = true
                        renderSubmitButton()
                        viewModel.loadRemoteEditorState(practicalId)
                    }
                    viewModel.resetSubmitState()
                }

                is UiState.Error -> {
                    progressSubmit.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnSaveServerDraft.isEnabled = true
                    btnLoadServerCopy.isEnabled = true
                    tvSubmitStatus.visibility = View.VISIBLE
                    tvSubmitStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
                    tvSubmitStatus.text = friendlyPracticalErrorMessage(state.message)
                    if (normalizePracticalErrorKey(state.message) == "PRACTICAL_PASSWORD_REQUIRED") {
                        showPracticalPasswordPromptForCurrentAction()
                    }
                    viewModel.resetSubmitState()
                }

                else -> {
                    progressSubmit.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    btnSaveServerDraft.isEnabled = true
                    btnLoadServerCopy.isEnabled = true
                }
            }
        }

        viewModel.draftState.observe(this) { draft ->
            if (draft != null) {
                if (etTheory.text.isBlank() && draft.theory.isNotBlank()) etTheory.setText(draft.theory)
                if (etCode.text.isBlank() && draft.code.isNotBlank()) etCode.setText(draft.code)
                if (etOutput.text.isBlank() && draft.output.isNotBlank()) etOutput.setText(draft.output)
                if (etError.text.isBlank() && draft.error.isNotBlank()) etError.setText(draft.error)
                if (etConclusion.text.isBlank() && draft.conclusion.isNotBlank()) etConclusion.setText(draft.conclusion)
                if (selectedLanguage() == languageOptions.first() && draft.language.isNotBlank()) setLanguage(draft.language)
                if (selectedPdfUri.isNullOrBlank() && !draft.pdfUri.isNullOrBlank()) {
                    selectedPdfUri = draft.pdfUri
                    selectedPdfName = draft.pdfName
                    renderAttachmentState()
                }
            }
            renderSyncStatus()
        }

        viewModel.practicalState.observe(this) { practical ->
            currentPracticalRecord = practical
            practical?.submissionId?.takeIf { it > 0 }?.let { submissionId = it }
            if (!practical?.practicalDoc.isNullOrBlank()) {
                val practicalDoc = practical?.practicalDoc
                remoteAttachmentPath = practicalDoc
                remoteAttachmentName = practicalDoc?.substringAfterLast('/')?.ifBlank { practicalDoc }
                cacheRemotePdfForOfflineIfNeeded()
            }
            renderAttachmentState()
            renderSyncStatus()
        }

        viewModel.remoteEditorState.observe(this) { state ->
            when (state) {
                is UiState.Success -> {
                    val data = state.data
                    if (data != null) {
                        applyRemoteEditorState(data)
                        if (manualRemoteLoad) {
                            Toast.makeText(
                                this,
                                if (data.source == RemoteEditorSource.SUBMISSION) {
                                    "Loaded server submission"
                                } else {
                                    "Loaded server draft"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else if (manualRemoteLoad) {
                        Toast.makeText(this, "No server draft or submission found", Toast.LENGTH_SHORT).show()
                    }
                    manualRemoteLoad = false
                    viewModel.resetRemoteEditorState()
                    viewModel.loadPracticalRecord(practicalId)
                }

                is UiState.Error -> {
                    if (manualRemoteLoad) {
                        Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    }
                    manualRemoteLoad = false
                    viewModel.resetRemoteEditorState()
                    viewModel.loadPracticalRecord(practicalId)
                }

                else -> Unit
            }
        }
    }

    private fun handleEditorLoginError(message: String) {
        val key = normalizePracticalErrorKey(message)
        val recoveryAction = pendingLoginRecovery

        tvSubmitStatus.visibility = View.VISIBLE
        tvSubmitStatus.setTextColor(resources.getColor(R.color.white_70, theme))
        tvSubmitStatus.text = when (key) {
            "PRACTICAL_WIFI_REQUIRED" ->
                "Offline mode: local drafts are available. Connect to college Wi-Fi to sync or submit."
            "PRACTICAL_SERVER_DOWN" ->
                "Offline mode: the practical server is not responding. Keep working locally and retry later."
            "PRACTICAL_PASSWORD_REQUIRED" ->
                "Practical login needs your LAS password before the app can sync with the server."
            else -> friendlyPracticalErrorMessage(message)
        }

        if (recoveryAction != null) {
            if (key == "PRACTICAL_PASSWORD_REQUIRED") {
                showPracticalPasswordPromptForCurrentAction()
            } else {
                pendingLoginRecovery = null
            }
        }
    }

    private fun applyRemoteEditorState(state: PracticalEditorState) {
        setLanguage(state.language)
        etTheory.setText(state.theory)
        etCode.setText(state.code)
        etOutput.setText(state.output)
        etError.setText(state.error)
        etConclusion.setText(state.conclusion)
        submissionId = state.submissionId ?: submissionId
        isSubmitted = state.source == RemoteEditorSource.SUBMISSION
        remoteAttachmentPath = state.practicalDoc
        remoteAttachmentName = state.practicalDoc?.substringAfterLast('/')?.ifBlank { state.practicalDoc }
        cacheRemotePdfForOfflineIfNeeded()
        if (state.evaluated) {
            btnSubmit.isEnabled = false
            btnSubmit.text = "Submission Locked"
            tvSubmitStatus.visibility = View.VISIBLE
            tvSubmitStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
            tvSubmitStatus.text = "This submission is already evaluated and cannot be updated."
        } else {
            btnSubmit.isEnabled = true
            renderSubmitButton()
        }
        renderAttachmentState()
        renderSyncStatus()
    }

    private fun openChatGPT(prompt: String, fieldType: String) {
        val intent = Intent(this, ChatGPTWebViewActivity::class.java).apply {
            putExtra(ChatGPTWebViewActivity.EXTRA_PROMPT, prompt)
            putExtra(ChatGPTWebViewActivity.EXTRA_FIELD_TYPE, fieldType)
        }
        chatGptLauncher.launch(intent)
    }

    private fun saveCurrentDraft() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(1000)
            persistDraftToDisk()
        }
    }

    private fun persistDraftToDisk() {
        val theory = etTheory.text.toString()
        val code = etCode.text.toString()
        val output = etOutput.text.toString()
        val error = etError.text.toString()
        val conclusion = etConclusion.text.toString()
        if (theory.isNotBlank() || code.isNotBlank() || output.isNotBlank() || error.isNotBlank() || conclusion.isNotBlank() || !selectedPdfUri.isNullOrBlank()) {
            viewModel.saveDraft(
                practicalId = practicalId,
                theory = theory,
                conclusion = conclusion,
                language = selectedLanguage(),
                code = code,
                output = output,
                error = error,
                pdfUri = selectedPdfUri,
                pdfName = selectedPdfName
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_EDIT_THEORY_PROMPT, 0, "Edit Theory Prompt")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, MENU_EDIT_CONCLUSION_PROMPT, 1, "Edit Conclusion Prompt")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, MENU_RESET_AI_PROMPTS, 2, "Reset AI Prompts")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, MENU_CLEAR_DRAFT, 3, "Clear Draft")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_EDIT_THEORY_PROMPT -> {
                showPromptEditor(
                    title = "Edit Theory Prompt",
                    currentValue = theoryPromptTemplate,
                    defaultValue = defaultTheoryPromptTemplate()
                ) { updated ->
                    theoryPromptTemplate = updated
                    lifecycleScope.launch {
                        UserPreferences(this@SubmitPracticalActivity).savePracticalTheoryPrompt(updated)
                    }
                }
                return true
            }

            MENU_EDIT_CONCLUSION_PROMPT -> {
                showPromptEditor(
                    title = "Edit Conclusion Prompt",
                    currentValue = conclusionPromptTemplate,
                    defaultValue = defaultConclusionPromptTemplate()
                ) { updated ->
                    conclusionPromptTemplate = updated
                    lifecycleScope.launch {
                        UserPreferences(this@SubmitPracticalActivity).savePracticalConclusionPrompt(updated)
                    }
                }
                return true
            }

            MENU_RESET_AI_PROMPTS -> {
                theoryPromptTemplate = defaultTheoryPromptTemplate()
                conclusionPromptTemplate = defaultConclusionPromptTemplate()
                lifecycleScope.launch {
                    val prefs = UserPreferences(this@SubmitPracticalActivity)
                    prefs.savePracticalTheoryPrompt(null)
                    prefs.savePracticalConclusionPrompt(null)
                }
                Toast.makeText(this, "AI prompts reset to default", Toast.LENGTH_SHORT).show()
                return true
            }

            MENU_CLEAR_DRAFT -> {
                AlertDialog.Builder(this)
                    .setTitle("Clear Draft?")
                    .setMessage("This will remove your saved local draft for this practical.")
                    .setPositiveButton("Clear") { _, _ ->
                        etTheory.setText("")
                        etCode.setText("")
                        etOutput.setText("")
                        etError.setText("")
                        etConclusion.setText("")
                        selectedPdfUri = null
                        selectedPdfName = null
                        removePdfOnSubmit = remoteAttachmentName != null
                        renderAttachmentState()
                        viewModel.deleteDraft(practicalId)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadPromptTemplates() {
        theoryPromptTemplate = defaultTheoryPromptTemplate()
        conclusionPromptTemplate = defaultConclusionPromptTemplate()
        lifecycleScope.launch {
            val prefs = UserPreferences(this@SubmitPracticalActivity)
            theoryPromptTemplate = prefs.getPracticalTheoryPrompt().orEmpty().ifBlank { defaultTheoryPromptTemplate() }
            conclusionPromptTemplate = prefs.getPracticalConclusionPrompt().orEmpty().ifBlank { defaultConclusionPromptTemplate() }
        }
    }

    private fun showPromptEditor(
        title: String,
        currentValue: String,
        defaultValue: String,
        onSave: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(currentValue)
            minLines = 10
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            hint = "Use {aim}, {subject}, {description}"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Placeholders available: {aim}, {subject}, {description}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text.toString().trim().ifBlank { defaultValue })
            }
            .setNeutralButton("Default") { _, _ ->
                onSave(defaultValue)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildPrompt(template: String): String {
        return template
            .replace("{aim}", practicalAim.ifBlank { "Not provided" })
            .replace("{subject}", subjectName.ifBlank { "Not provided" })
            .replace("{description}", practicalDesc.ifBlank { "Not provided" })
    }

    private fun defaultTheoryPromptTemplate(): String = """
        Write a detailed theory for the following practical aim: {aim}.
        Subject/Lab: {subject}
        Context: {description}

        IMPORTANT INSTRUCTIONS:
        1. Provide ONLY the theory content.
        2. Do NOT include any introductory phrases (like "Sure, here is...") or concluding questions.
        3. DO NOT use any markdown formatting like bold (**words**) or italics. Use plain text only.
        4. Maintain a formal academic tone.
    """.trimIndent()

    private fun defaultConclusionPromptTemplate(): String = """
        Write a concise conclusion for the following practical aim: {aim}.
        Subject/Lab: {subject}
        Context: {description}

        IMPORTANT INSTRUCTIONS:
        1. Provide ONLY the conclusion text.
        2. Do NOT include any introductory phrases or follow-up questions.
        3. DO NOT use any markdown formatting like bold (**words**) or italics. Use plain text only.
        4. Keep it professional and direct.
    """.trimIndent()

    private fun renderAttachmentState() {
        tvAttachmentName.text = selectedPdfName?.let { "Selected: $it" } ?: "No PDF selected"
        if (remoteAttachmentName.isNullOrBlank()) {
            tvRemoteAttachment.visibility = View.GONE
        } else {
            tvRemoteAttachment.visibility = View.VISIBLE
            tvRemoteAttachment.text =
                if (removePdfOnSubmit && selectedPdfUri == null) {
                    "Server file will be removed on next submit: $remoteAttachmentName"
                } else {
                    "Server file: $remoteAttachmentName"
                }
        }

        val previewLabel = when {
            !selectedPdfUri.isNullOrBlank() -> "Preview Selected PDF"
            !remoteAttachmentPath.isNullOrBlank() && !(removePdfOnSubmit && selectedPdfUri == null) -> "Preview Submitted PDF"
            else -> null
        }
        if (previewLabel == null) {
            btnPreviewPdf.visibility = View.GONE
        } else {
            btnPreviewPdf.visibility = View.VISIBLE
            btnPreviewPdf.text = previewLabel
        }
    }

    private fun renderSubmitButton() {
        btnSubmit.text = if (isSubmitted || submissionId != null) "Update Practical" else "Submit Practical"
    }

    private fun renderSyncStatus() {
        val practical = currentPracticalRecord
        val syncError = practical?.lastSyncError?.takeIf { it.isNotBlank() }
        val localSavedAt = practical?.localDraftSavedAt ?: viewModel.draftState.value?.lastSaved
        val serverSyncAt = practical?.lastServerSyncAt ?: viewModel.draftState.value?.lastServerSyncAt
        val serverVerifiedAt = practical?.lastServerVerifiedAt ?: viewModel.draftState.value?.lastServerVerifiedAt
        val hasUnsyncedLocalDraft = localSavedAt != null && (serverSyncAt == null || localSavedAt > serverSyncAt)

        val headline = when {
            practical?.isSubmitted == true -> "Submitted and server verified"
            syncError != null -> "Last server sync failed"
            hasUnsyncedLocalDraft -> "Draft saved locally"
            serverVerifiedAt != null -> "Server copy verified"
            else -> "Offline cache only"
        }

        tvSyncHeadline.text = headline
        tvLocalDraftMeta.text = "Local save: ${formatTimestamp(localSavedAt)}"
        tvServerSyncMeta.text = "Last server sync: ${formatTimestamp(serverSyncAt)}"
        tvServerVerifyMeta.text = "Last server verification: ${formatTimestamp(serverVerifiedAt)}"

        if (syncError == null) {
            tvSyncError.visibility = View.GONE
        } else {
            tvSyncError.visibility = View.VISIBLE
            tvSyncError.text = "Last sync failed: ${friendlyPracticalErrorMessage(syncError)}"
        }

        val pendingSyncAction = practical?.pendingSyncAction
        val shouldShowRetry = syncError != null || hasUnsyncedLocalDraft || pendingSyncAction != null
        if (shouldShowRetry) {
            btnRetrySync.visibility = View.VISIBLE
            btnRetrySync.text = when {
                pendingSyncAction == PracticalRepository.SYNC_ACTION_SUBMIT -> "Retry Submit"
                pendingSyncAction == PracticalRepository.SYNC_ACTION_UPDATE -> "Retry Update"
                else -> "Sync Draft To Server"
            }
        } else {
            btnRetrySync.visibility = View.GONE
        }
    }

    private fun selectedLanguage(): String {
        return spinnerLanguage.selectedItem?.toString()?.trim().orEmpty().ifBlank { "python" }
    }

    private fun setLanguage(value: String) {
        val index = languageOptions.indexOfFirst { it.equals(value, ignoreCase = true) }
        spinnerLanguage.setSelection(if (index >= 0) index else 0)
    }

    private fun effectiveErrorText(): String {
        val explicit = etError.text.toString().trim()
        if (explicit.isNotBlank()) return explicit
        val output = etOutput.text.toString().trim()
        return if (output.contains("error", ignoreCase = true)) output else ""
    }

    private fun successMessageForAction(): String {
        return when (pendingAction) {
            PendingAction.SAVE_DRAFT -> "Server draft saved."
            PendingAction.UPDATE -> "Practical updated successfully."
            PendingAction.SUBMIT -> "Practical submitted successfully."
        }
    }

    private fun loadingMessageForAction(): String {
        return when (pendingAction) {
            PendingAction.SAVE_DRAFT -> "Saving server draft. If the session expired, the app will refresh it automatically."
            PendingAction.UPDATE -> "Checking practical session and updating your submission."
            PendingAction.SUBMIT -> "Checking practical session and submitting your practical."
        }
    }

    private fun normalizePracticalErrorKey(message: String): String {
        val lower = message.lowercase()
        return when {
            message == "PRACTICAL_PASSWORD_REQUIRED" -> message
            message == "PRACTICAL_WIFI_REQUIRED" -> message
            message == "PRACTICAL_SERVER_DOWN" -> message
            lower.contains("session") && lower.contains("expired") -> "PRACTICAL_SESSION_EXPIRED"
            lower.contains("token") && lower.contains("expired") -> "PRACTICAL_SESSION_EXPIRED"
            lower.contains("unauthorized") ||
                lower.contains("unauthenticated") ||
                lower.contains("invalid token") ||
                lower.contains("login required") ||
                lower.contains("please login") ||
                lower.contains("please log in") ||
                lower.contains("not logged in") ||
                lower.contains("401") ||
                lower.contains("403") ||
                lower.contains("419") -> "PRACTICAL_SESSION_EXPIRED"
            lower.contains("user not found") ||
                lower.contains("invalid") ||
                lower.contains("credential") ||
                lower.contains("password") -> "PRACTICAL_PASSWORD_REQUIRED"
            lower.contains("unable to resolve host") ||
                lower.contains("failed to connect") ||
                lower.contains("network is unreachable") ||
                lower.contains("connection refused") ||
                lower.contains("timeout") ||
                lower.contains("timed out") -> "PRACTICAL_WIFI_REQUIRED"
            lower.contains("521") ||
                lower.contains("522") ||
                lower.contains("502") ||
                lower.contains("503") ||
                lower.contains("504") -> "PRACTICAL_SERVER_DOWN"
            else -> message
        }
    }

    private fun friendlyPracticalErrorMessage(message: String): String {
        return when (normalizePracticalErrorKey(message)) {
            "PRACTICAL_PASSWORD_REQUIRED" ->
                "Practical login needs your LAS password. Enter it once and this action will retry."
            "PRACTICAL_WIFI_REQUIRED" ->
                "Connect to college Wi-Fi, then retry. Your local practical draft is still saved on this device."
            "PRACTICAL_SERVER_DOWN" ->
                "The practical server is not responding. Keep working locally and retry on college Wi-Fi later."
            "PRACTICAL_SESSION_EXPIRED" ->
                "The practical session expired and could not be refreshed. Reconnect to college Wi-Fi and retry."
            else -> message
        }
    }

    private fun showPracticalPasswordPromptForCurrentAction() {
        pendingLoginRecovery = pendingAction
        val input = EditText(this).apply {
            hint = "Practical password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Practical Password Needed")
            .setMessage("Enter your LAS/practical password. After login succeeds, the app will retry this action automatically.")
            .setView(input)
            .setPositiveButton("Login") { _, _ ->
                val password = input.text.toString().trim()
                if (password.isBlank()) {
                    Toast.makeText(this, "Password is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.retryLoginWithPassword(password, loadSubjects = false)
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingLoginRecovery = null
            }
            .show()
    }

    private fun showAiWifiNote() {
        Toast.makeText(
            this,
            "On college Wi-Fi, ChatGPT help may be slow or blocked due to campus network restrictions.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun previewPdf() {
        when {
            !selectedPdfUri.isNullOrBlank() -> openLocalPdf(Uri.parse(selectedPdfUri))
            !remoteAttachmentPath.isNullOrBlank() && !(removePdfOnSubmit && selectedPdfUri == null) -> {
                val uri = normalizeRemotePdfUri(remoteAttachmentPath!!)
                if (uri != null) {
                    openRemotePdf(uri)
                } else {
                    Toast.makeText(this, "Submitted PDF preview is not available for this path yet.", Toast.LENGTH_LONG).show()
                }
            }
            else -> Toast.makeText(this, "No PDF available to preview.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLocalPdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchPdfIntent(intent)
    }

    private fun openRemotePdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        launchPdfIntent(intent)
    }

    private fun launchPdfIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to preview this PDF.", Toast.LENGTH_LONG).show()
        }
    }

    private fun normalizeRemotePdfUri(rawPath: String): Uri? {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.toUri()
        }
        return when {
            trimmed.startsWith("/") -> "http://10.10.30.203$trimmed".toUri()
            trimmed.contains("/") -> "http://10.10.30.203/$trimmed".toUri()
            else -> null
        }
    }

    private fun cacheRemotePdfForOfflineIfNeeded() {
        val remotePath = remoteAttachmentPath?.takeIf { it.isNotBlank() } ?: return
        val remoteName = remoteAttachmentName?.takeIf { it.isNotBlank() } ?: "practical_$practicalId.pdf"
        if (!selectedPdfUri.isNullOrBlank()) return

        lifecycleScope.launch {
            val cachedUri = withContext(Dispatchers.IO) {
                cacheRemotePdf(remotePath, remoteName)
            } ?: return@launch

            if (selectedPdfUri.isNullOrBlank()) {
                selectedPdfUri = cachedUri.toString()
                selectedPdfName = remoteName
                removePdfOnSubmit = false
                renderAttachmentState()
                persistDraftToDisk()
            }
        }
    }

    private fun cacheRemotePdf(remotePath: String, fileName: String): Uri? {
        val remoteUri = normalizeRemotePdfUri(remotePath) ?: return null
        return try {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "practical_$practicalId.pdf" }
            val cacheDir = File(getExternalFilesDir("practical_pdfs"), "server_cache").apply { mkdirs() }
            val target = File(cacheDir, "practical_${practicalId}_$safeName")
            if (!target.exists() || target.length() == 0L) {
                URL(remoteUri.toString()).openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            FileProvider.getUriForFile(this, "${packageName}.provider", target)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return "practical.pdf"
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun pasteToEditText(editText: EditText, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasteData = item?.text
            if (pasteData != null) {
                editText.setText(pasteData)
                Toast.makeText(this, "Pasted into $label", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty or not text", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        saveJob?.cancel()
        persistDraftToDisk()
    }

    private fun formatTimestamp(value: Long?): String {
        if (value == null || value <= 0L) return "Not available"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(value))
    }

    private enum class PendingAction {
        SAVE_DRAFT,
        SUBMIT,
        UPDATE
    }
}
