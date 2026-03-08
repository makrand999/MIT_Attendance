package com.mit.attendance.ui.practical

import androidx.lifecycle.*
import com.mit.attendance.data.PracticalRepository
import com.mit.attendance.data.api.GeminiService
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*
import kotlinx.coroutines.launch

sealed class UiState<out T> {
    object Idle    : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

class PracticalViewModel(private val userPrefs: UserPreferences) : ViewModel() {

    private var repo: PracticalRepository? = null

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

    // ── Init ───────────────────────────────────────────────────────────────

    fun init() {
        if (repo != null) return
        viewModelScope.launch {
            val email = userPrefs.getEmail() ?: ""
            repo = PracticalRepository(email)
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
                    _loginState.value = UiState.Error(it.message ?: "Login failed")
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
                .onSuccess  { _submitState.value = UiState.Success(Unit) }
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
}

class PracticalViewModelFactory(private val userPrefs: UserPreferences) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PracticalViewModel(userPrefs) as T
}
