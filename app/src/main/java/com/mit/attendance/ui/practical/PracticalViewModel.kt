package com.mit.attendance.ui.practical

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.*
import com.mit.attendance.data.PracticalRepository
import com.mit.attendance.data.api.GeminiService
import com.mit.attendance.data.db.AppDatabase
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*
import kotlinx.coroutines.launch

sealed class UiState<out T> {
    object Idle    : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

class PracticalViewModel(application: Application, private val userPrefs: UserPreferences) : AndroidViewModel(application) {

    private var repo: PracticalRepository? = null
    private val db = AppDatabase.getDatabase(application)
    private val draftDao = db.practicalDraftDao()
    private val practicalDao = db.practicalDao()

    // ── Login ──────────────────────────────────────────────────────────────
    private val _loginState = MutableLiveData<UiState<Unit>>(UiState.Idle)
    val loginState: LiveData<UiState<Unit>> = _loginState

    // ── Subjects ───────────────────────────────────────────────────────────
    private val _subjects = MutableLiveData<UiState<List<PracticalSubject>>>(UiState.Idle)
    val subjects: LiveData<UiState<List<PracticalSubject>>> = _subjects
    private val _serverStatus = MutableLiveData(PracticalServerStatus.UNKNOWN)
    val serverStatus: LiveData<PracticalServerStatus> = _serverStatus

    // ── Practicals ─────────────────────────────────────────────────────────
    private val _practicals = MutableLiveData<UiState<PracticalsResponse>>(UiState.Idle)
    val practicals: LiveData<UiState<PracticalsResponse>> = _practicals

    // ── Submit ─────────────────────────────────────────────────────────────
    private val _submitState = MutableLiveData<UiState<Unit>>(UiState.Idle)
    val submitState: LiveData<UiState<Unit>> = _submitState

    // ── AI generation ──────────────────────────────────────────────────────
    private val _theoryAiState = MutableLiveData<UiState<String>>(UiState.Idle)
    val theoryAiState: LiveData<UiState<String>> = _theoryAiState

    private val _conclusionAiState = MutableLiveData<UiState<String>>(UiState.Idle)
    val conclusionAiState: LiveData<UiState<String>> = _conclusionAiState

    // ── Drafts ─────────────────────────────────────────────────────────────
    private val _draftState = MutableLiveData<PracticalDraft?>(null)
    val draftState: LiveData<PracticalDraft?> = _draftState

    private val _remoteEditorState = MutableLiveData<UiState<PracticalEditorState?>>(UiState.Idle)
    val remoteEditorState: LiveData<UiState<PracticalEditorState?>> = _remoteEditorState

    private val _practicalState = MutableLiveData<Practical?>(null)
    val practicalState: LiveData<Practical?> = _practicalState

    // ── Init ───────────────────────────────────────────────────────────────

    fun init(loadSubjects: Boolean = true) {
        if (repo != null) return
        viewModelScope.launch {
            val email = userPrefs.getEmail() ?: ""
            val r = PracticalRepository(email, practicalDao, userPrefs)
            r.restoreSession()
            repo = r
            login(loadSubjects)
        }
    }

    private fun login(loadSubjects: Boolean) {
        val currentRepo = repo ?: return
        _loginState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.login()
                .onSuccess {
                    _loginState.value = UiState.Success(Unit)
                    if (loadSubjects) {
                        loadSubjects()
                    }
                }
                .onFailure {
                    _loginState.value = UiState.Error(it.message ?: "Login failed")
                    if (loadSubjects) {
                        // Even if login fails (offline), we still try to load subjects from cache.
                        loadSubjects()
                    }
                }
        }
    }

    fun retryLoginWithPassword(password: String, loadSubjects: Boolean = true) {
        val currentRepo = repo ?: return
        _loginState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.login(passwordOverride = password)
                .onSuccess {
                    _loginState.value = UiState.Success(Unit)
                    if (loadSubjects) {
                        loadSubjects()
                    }
                }
                .onFailure {
                    _loginState.value = UiState.Error(it.message ?: "Login failed")
                }
        }
    }

    fun retryLoginAndLoadSubjects() {
        val currentRepo = repo ?: return
        _loginState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.login()
                .onSuccess {
                    _loginState.value = UiState.Success(Unit)
                    loadSubjects()
                }
                .onFailure {
                    _loginState.value = UiState.Error(it.message ?: "Login failed")
                }
        }
    }

    fun loadSubjects() {
        val currentRepo = repo ?: return
        _subjects.value = UiState.Loading
        viewModelScope.launch {
            val cached = currentRepo.getCachedPracticalSubjects()
            if (cached.isNotEmpty()) {
                _subjects.value = UiState.Success(cached)
            }

            _serverStatus.value = currentRepo.checkServerAvailability()

            currentRepo.refreshPracticalSubjects()
                .onSuccess { refreshed ->
                    _subjects.value = UiState.Success(refreshed)
                    _serverStatus.value = PracticalServerStatus.AVAILABLE
                }
                .onFailure {
                    if (cached.isEmpty()) {
                        _subjects.value = UiState.Error(it.message ?: "Error")
                    }
                }
        }
    }

    fun loadPracticals(subjectId: Int) {
        val currentRepo = repo ?: return
        _practicals.value = UiState.Loading
        viewModelScope.launch {
            val cached = currentRepo.getCachedPracticals(subjectId)
            if (cached != null) {
                _practicals.value = UiState.Success(cached)
            }

            val status = currentRepo.checkServerAvailability()
            _serverStatus.value = status

            currentRepo.refreshPracticals(subjectId)
                .onSuccess {
                    _practicals.value = UiState.Success(it)
                    _serverStatus.value = PracticalServerStatus.AVAILABLE
                }
                .onFailure {
                    if (cached == null) {
                        _practicals.value = UiState.Error(it.message ?: "Error")
                    }
                }
        }
    }

    fun submitPractical(
        subjectId: Int,
        practicalId: Int,
        submissionId: Int?,
        theory: String,
        conclusion: String,
        language: String,
        code: String,
        output: String,
        error: String,
        pdfUri: String?,
        removePdf: Boolean = false
    ) {
        val currentRepo = repo ?: return
        _submitState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.submitPractical(
                subjectId = subjectId,
                practicalId = practicalId,
                submissionId = submissionId,
                theory = theory,
                conclusion = conclusion,
                language = language,
                code = code,
                output = output,
                error = error,
                pdfAttachment = readAttachment(pdfUri),
                removePdf = removePdf
            )
                .onSuccess  { 
                    _submitState.value = UiState.Success(Unit)
                    deleteDraft(practicalId) // Clear draft on successful submit
                    loadPracticalRecord(practicalId)
                }
                .onFailure  {
                    upsertPendingDraftFailure(
                        practicalId = practicalId,
                        theory = theory,
                        conclusion = conclusion,
                        language = language,
                        code = code,
                        output = output,
                        error = error,
                        pdfUri = pdfUri,
                        pendingSyncAction = if (submissionId != null) {
                            PracticalRepository.SYNC_ACTION_UPDATE
                        } else {
                            PracticalRepository.SYNC_ACTION_SUBMIT
                        },
                        message = it.message ?: "Submit failed"
                    )
                    _submitState.value = UiState.Error(it.message ?: "Submit failed")
                    loadPracticalRecord(practicalId)
                }
        }
    }

    fun resetSubmitState() { _submitState.value = UiState.Idle }

    // ── AI helpers ─────────────────────────────────────────────────────────

    fun generateTheory(aim: String, description: String?) {
        _theoryAiState.value = UiState.Loading
        viewModelScope.launch {
            GeminiService.generateTheory(aim, description)
                .onSuccess  { _theoryAiState.value = UiState.Success(it) }
                .onFailure  { _theoryAiState.value = UiState.Error(it.message ?: "AI error") }
        }
    }

    fun generateConclusion(aim: String, description: String?) {
        _conclusionAiState.value = UiState.Loading
        viewModelScope.launch {
            GeminiService.generateConclusion(aim, description)
                .onSuccess  { _conclusionAiState.value = UiState.Success(it) }
                .onFailure  { _conclusionAiState.value = UiState.Error(it.message ?: "AI error") }
        }
    }

    fun resetTheoryAiState()     { _theoryAiState.value     = UiState.Idle }
    fun resetConclusionAiState() { _conclusionAiState.value = UiState.Idle }

    // ── Draft Helpers ──────────────────────────────────────────────────────

    fun saveDraft(
        practicalId: Int,
        theory: String,
        conclusion: String,
        language: String = "python",
        code: String = "",
        output: String = "",
        error: String = "",
        pdfUri: String? = null,
        pdfName: String? = null
    ) {
        viewModelScope.launch {
            val existing = draftDao.getDraft(practicalId)
            val savedAt = System.currentTimeMillis()
            draftDao.upsertDraft(
                PracticalDraft(
                    practicalId = practicalId,
                    language = language,
                    theory = theory,
                    code = code,
                    output = output,
                    error = error,
                    conclusion = conclusion,
                    pdfUri = pdfUri,
                    pdfName = pdfName,
                    lastServerSyncAt = existing?.lastServerSyncAt,
                    lastServerVerifiedAt = existing?.lastServerVerifiedAt,
                    pendingSyncAction = existing?.pendingSyncAction,
                    lastSyncError = existing?.lastSyncError,
                    lastSaved = savedAt
                )
            )
            practicalDao.updateLocalDraftTimestamp(practicalId, savedAt)
            loadPracticalRecord(practicalId)
        }
    }

    fun saveServerDraft(
        subjectId: Int,
        practicalId: Int,
        theory: String,
        conclusion: String,
        language: String,
        code: String,
        output: String,
        error: String,
        pdfUri: String?
    ) {
        val currentRepo = repo ?: return
        _submitState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.saveServerDraft(
                subjectId = subjectId,
                practicalId = practicalId,
                theory = theory,
                conclusion = conclusion,
                language = language,
                code = code,
                output = output,
                error = error,
                pdfAttachment = readAttachment(pdfUri)
            )
                .onSuccess {
                    val existingDraft = draftDao.getDraft(practicalId)
                    if (existingDraft != null) {
                        draftDao.upsertDraft(
                            existingDraft.copy(
                                lastServerSyncAt = System.currentTimeMillis(),
                                lastServerVerifiedAt = System.currentTimeMillis(),
                                pendingSyncAction = null,
                                lastSyncError = null
                            )
                        )
                    }
                    _submitState.value = UiState.Success(Unit)
                    loadPracticalRecord(practicalId)
                }
                .onFailure {
                    upsertPendingDraftFailure(
                        practicalId = practicalId,
                        theory = theory,
                        conclusion = conclusion,
                        language = language,
                        code = code,
                        output = output,
                        error = error,
                        pdfUri = pdfUri,
                        pendingSyncAction = PracticalRepository.SYNC_ACTION_SAVE_DRAFT,
                        message = it.message ?: "Draft save failed"
                    )
                    _submitState.value = UiState.Error(it.message ?: "Draft save failed")
                    loadPracticalRecord(practicalId)
                }
        }
    }

    fun loadRemoteEditorState(practicalId: Int) {
        val currentRepo = repo ?: return
        _remoteEditorState.value = UiState.Loading
        viewModelScope.launch {
                currentRepo.loadRemoteEditorState(practicalId)
                .onSuccess {
                    val existingDraft = draftDao.getDraft(practicalId)
                    if (existingDraft != null) {
                        draftDao.upsertDraft(
                            existingDraft.copy(lastServerVerifiedAt = System.currentTimeMillis())
                        )
                    }
                    _remoteEditorState.value = UiState.Success(it)
                    loadPracticalRecord(practicalId)
                }
                .onFailure { _remoteEditorState.value = UiState.Error(it.message ?: "Remote load failed") }
        }
    }

    fun resetRemoteEditorState() {
        _remoteEditorState.value = UiState.Idle
    }

    fun refreshServerStatus() {
        val currentRepo = repo ?: return
        viewModelScope.launch {
            _serverStatus.value = currentRepo.checkServerAvailability()
        }
    }

    fun loadDraft(practicalId: Int) {
        viewModelScope.launch {
            _draftState.value = draftDao.getDraft(practicalId)
        }
    }

    fun loadPracticalRecord(practicalId: Int) {
        viewModelScope.launch {
            _practicalState.value = practicalDao.getPracticalById(practicalId)
        }
    }

    fun deleteDraft(practicalId: Int) {
        viewModelScope.launch {
            draftDao.deleteDraft(practicalId)
            practicalDao.updateLocalDraftTimestamp(practicalId, null)
            _draftState.value = null
            loadPracticalRecord(practicalId)
        }
    }

    fun retryPendingSync(
        subjectId: Int,
        practicalId: Int,
        submissionId: Int?,
        theory: String,
        conclusion: String,
        language: String,
        code: String,
        output: String,
        error: String,
        pdfUri: String?,
        removePdf: Boolean,
        pendingSyncAction: String?
    ) {
        when (pendingSyncAction) {
            PracticalRepository.SYNC_ACTION_SUBMIT,
            PracticalRepository.SYNC_ACTION_UPDATE -> {
                submitPractical(
                    subjectId = subjectId,
                    practicalId = practicalId,
                    submissionId = submissionId,
                    theory = theory,
                    conclusion = conclusion,
                    language = language,
                    code = code,
                    output = output,
                    error = error,
                    pdfUri = pdfUri,
                    removePdf = removePdf
                )
            }

            else -> {
                saveServerDraft(
                    subjectId = subjectId,
                    practicalId = practicalId,
                    theory = theory,
                    conclusion = conclusion,
                    language = language,
                    code = code,
                    output = output,
                    error = error,
                    pdfUri = pdfUri
                )
            }
        }
    }

    private fun readAttachment(pdfUri: String?): PracticalAttachment? {
        val uri = pdfUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val resolver = getApplication<Application>().contentResolver
        val fileName = queryFileName(resolver, uri) ?: "practical.pdf"
        val mimeType = resolver.getType(uri) ?: "application/pdf"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to read selected attachment")
        return PracticalAttachment(fileName = fileName, bytes = bytes, mimeType = mimeType)
    }

    private fun queryFileName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    return cursor.getString(index)
                }
            }
        }
        return null
    }

    private suspend fun upsertPendingDraftFailure(
        practicalId: Int,
        theory: String,
        conclusion: String,
        language: String,
        code: String,
        output: String,
        error: String,
        pdfUri: String?,
        pendingSyncAction: String,
        message: String
    ) {
        val existingDraft = draftDao.getDraft(practicalId)
        val savedAt = System.currentTimeMillis()
        draftDao.upsertDraft(
            (existingDraft ?: PracticalDraft(
                practicalId = practicalId,
                language = language,
                theory = theory,
                code = code,
                output = output,
                error = error,
                conclusion = conclusion,
                pdfUri = pdfUri,
                pdfName = null,
                lastSaved = savedAt
            )).copy(
                language = language,
                theory = theory,
                code = code,
                output = output,
                error = error,
                conclusion = conclusion,
                pdfUri = pdfUri ?: existingDraft?.pdfUri,
                pdfName = existingDraft?.pdfName,
                pendingSyncAction = pendingSyncAction,
                lastSyncError = message,
                lastSaved = savedAt
            )
        )
        practicalDao.updateLocalDraftTimestamp(practicalId, savedAt)
        practicalDao.updateSyncFailureState(practicalId, pendingSyncAction, message)
    }
}

class PracticalViewModelFactory(private val application: Application, private val userPrefs: UserPreferences) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PracticalViewModel(application, userPrefs) as T
}
