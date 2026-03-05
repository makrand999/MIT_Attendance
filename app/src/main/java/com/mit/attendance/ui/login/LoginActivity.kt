package com.mit.attendance.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.*
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivityLoginBinding
import com.mit.attendance.model.LoginResult
import com.mit.attendance.model.SingleLiveEvent
import com.mit.attendance.service.AttendanceSyncWorker
import com.mit.attendance.ui.subjects.SubjectsActivity
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatDelegate
// ── Splash Activity ───────────────────────────────────────────────────────────

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { true }
        lifecycleScope.launch {
            val repo = AttendanceRepository(applicationContext)
            val isLoggedIn = repo.prefs.isLoggedInSnapshot()
            startActivity(
                if (isLoggedIn) Intent(this@SplashActivity, SubjectsActivity::class.java)
                else Intent(this@SplashActivity, LoginActivity::class.java)
            )
            finish()
        }
    }
}

// ── Login ViewModel ───────────────────────────────────────────────────────────

class LoginViewModel(private val repo: AttendanceRepository) : ViewModel() {

    private val _loginState = MutableLiveData<LoginUiState>(LoginUiState.Idle)
    val loginState: LiveData<LoginUiState> = _loginState

    fun login(email: String, password: String, semId: Int?) {
        when {
            email.isBlank() || password.isBlank() -> {
                _loginState.value = LoginUiState.Error("Please fill in all fields")
                return
            }
            // FIX: semId null means the spinner text was unrecognisable — surface error
            // instead of silently defaulting to semester 1.
            semId == null -> {
                _loginState.value = LoginUiState.Error("Please select a valid semester")
                return
            }
        }
        _loginState.value = LoginUiState.Loading
        viewModelScope.launch {
            _loginState.value = when (val result = repo.login(email, password, semId!!)) {
                is LoginResult.Success            -> LoginUiState.Success
                is LoginResult.InvalidCredentials -> LoginUiState.Error("Invalid email or password")
                is LoginResult.ServerDown         -> LoginUiState.ServerDown
                is LoginResult.Error              -> LoginUiState.Error(result.message)
            }
        }
    }
}

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    object Success : LoginUiState()
    object ServerDown : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

class LoginViewModelFactory(private val context: android.content.Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LoginViewModel(AttendanceRepository(context)) as T
    }
}

// ── Login Activity ────────────────────────────────────────────────────────────

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels { LoginViewModelFactory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSemesterDropdown()
        setupObservers()
        setupClickListeners()
    }

    private fun setupSemesterDropdown() {
        val semOptions = (1..8).map { "Semester $it" }
        val adapter = ArrayAdapter(this, R.layout.item_dropdown, semOptions)
        binding.spinnerSemester.setAdapter(adapter)
        binding.spinnerSemester.setText("Semester 1", false)
    }

    private fun setupObservers() {
        viewModel.loginState.observe(this) { state ->
            when (state) {
                is LoginUiState.Loading -> {
                    binding.btnLogin.isEnabled = false
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.visibility = View.GONE
                }
                is LoginUiState.Success -> {
                    AttendanceSyncWorker.createNotificationChannel(applicationContext)
                    AttendanceSyncWorker.schedule(applicationContext)
                    AttendanceSyncWorker.runNow(applicationContext)
                    startActivity(Intent(this, SubjectsActivity::class.java))
                    finish()
                }
                is LoginUiState.ServerDown -> {
                    binding.btnLogin.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "⚠️ Server appears to be down. Please try again later."
                }
                is LoginUiState.Error -> {
                    binding.btnLogin.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "❌ ${state.message}"
                }
                is LoginUiState.Idle -> {
                    binding.btnLogin.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.GONE
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val semText = binding.spinnerSemester.text.toString()
            // FIX: returns null if text doesn't contain a digit (e.g. cleared field),
            // which the ViewModel now handles with a proper error message.
            val semId = semText.filter { it.isDigit() }.toIntOrNull()
            viewModel.login(email, password, semId)
        }
    }
}