package com.mit.attendance.ui.practical

import android.app.Application
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

    // ── Init ───────────────────────────────────────────────────────────────

    fun init() {
        if (repo != null) return
        viewModelScope.launch {
            val email = userPrefs.getEmail() ?: ""
            val r = PracticalRepository(email, practicalDao, userPrefs)
            r.restoreSession()
            repo = r
            loginAndLoad()
        }
    }

    private fun loginAndLoad() {
        val currentRepo = repo ?: return
        _loginState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.login()
                .onSuccess {
                    _loginState.value = UiState.Success(Unit)
                    loadSubjects()
                }
                .onFailure {
                    // Even if login fails (offline), we still try to load subjects from cache
                    _loginState.value = UiState.Error(it.message ?: "Login failed")
                    loadSubjects() 
                }
        }
    }

    fun loadSubjects() {
        val currentRepo = repo ?: return
        _subjects.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.getPracticalSubjects()
                .onSuccess  { _subjects.value = UiState.Success(it) }
                .onFailure  { _subjects.value = UiState.Error(it.message ?: "Error") }
        }
    }

    fun loadPracticals(subjectId: Int) {
        val currentRepo = repo ?: return
        _practicals.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.getPracticals(subjectId)
                .onSuccess  { _practicals.value = UiState.Success(it) }
                .onFailure  { _practicals.value = UiState.Error(it.message ?: "Error") }
        }
    }

    fun submitPractical(
        subjectId: Int,
        practicalId: Int,
        practicalNumber: Int,
        theory: String,
        conclusion: String
    ) {
        val currentRepo = repo ?: return
        _submitState.value = UiState.Loading
        viewModelScope.launch {
            currentRepo.submitPractical(subjectId, practicalId, practicalNumber, theory, conclusion)
                .onSuccess  { 
                    _submitState.value = UiState.Success(Unit)
                    deleteDraft(practicalId) // Clear draft on successful submit
                }
                .onFailure  { _submitState.value = UiState.Error(it.message ?: "Submit failed") }
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

    fun saveDraft(practicalId: Int, theory: String, conclusion: String) {
        viewModelScope.launch {
            draftDao.upsertDraft(PracticalDraft(practicalId, theory, conclusion))
        }
    }

    fun loadDraft(practicalId: Int) {
        viewModelScope.launch {
            _draftState.value = draftDao.getDraft(practicalId)
        }
    }

    fun deleteDraft(practicalId: Int) {
        viewModelScope.launch {
            draftDao.deleteDraft(practicalId)
            _draftState.value = null
        }
    }
}

class PracticalViewModelFactory(private val application: Application, private val userPrefs: UserPreferences) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PracticalViewModel(application, userPrefs) as T
}
