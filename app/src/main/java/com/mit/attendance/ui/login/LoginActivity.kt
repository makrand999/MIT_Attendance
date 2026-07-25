package com.mit.attendance.ui.login

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.*
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivityLoginBinding
import com.mit.attendance.model.LoginResult
import com.mit.attendance.service.AttendanceSyncWorker
import com.mit.attendance.ui.subjects.ErpWebViewActivity
import com.mit.attendance.ui.subjects.SubjectsActivity
import com.mit.attendance.ui.theme.MedKitMotion
import com.mit.attendance.ui.timetable.TimetableActivity
import kotlinx.coroutines.launch

// ── Splash Activity ───────────────────────────────────────────────────────────

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { true }
        lifecycleScope.launch {
            val repo = AttendanceRepository(applicationContext)
            val isLoggedIn = repo.prefs.isLoggedInSnapshot()
            
            val intent = if (isLoggedIn) {
                // Handle App Shortcuts
                when (intent.getStringExtra("shortcut")) {
                    "timetable" -> Intent(this@SplashActivity, TimetableActivity::class.java)
                    "erp" -> Intent(this@SplashActivity, ErpWebViewActivity::class.java)
                    else -> Intent(this@SplashActivity, SubjectsActivity::class.java)
                }
            } else {
                Intent(this@SplashActivity, LoginActivity::class.java)
            }
            
            startActivity(intent)
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
    private var semesterPickerVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSemesterDropdown()
        setupObservers()
        setupClickListeners()
        MedKitMotion.playEntrance(binding.cardLogin)
        MedKitMotion.applyFloat(binding.cardLogin, distanceDp = 5f, durationMs = 3200L)
    }

    private fun setupSemesterDropdown() {
        binding.spinnerSemester.setText("Semester 1")
        binding.spinnerSemester.setOnClickListener {
            hideKeyboard()
            moveLoginContentForKeyboard(false)
            toggleSemesterPicker()
        }
        binding.tilSemester.setEndIconOnClickListener {
            hideKeyboard()
            moveLoginContentForKeyboard(false)
            toggleSemesterPicker()
        }

        listOf(
            binding.btnSemester1 to 1,
            binding.btnSemester2 to 2,
            binding.btnSemester3 to 3,
            binding.btnSemester4 to 4,
            binding.btnSemester5 to 5,
            binding.btnSemester6 to 6,
            binding.btnSemester7 to 7,
            binding.btnSemester8 to 8,
        ).forEach { (button, semester) ->
            button.scaleX = 0.86f
            button.scaleY = 0.86f
            button.setOnClickListener {
                binding.spinnerSemester.setText("Semester $semester")
                hideSemesterPicker()
            }
        }
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
                    AttendanceSyncWorker.createNotificationChannels(applicationContext)
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
        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideSemesterPicker()
                moveLoginContentForKeyboard(true)
                liftLoginCardForKeyboard()
            }
        }
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideSemesterPicker()
                moveLoginContentForKeyboard(true)
                liftLoginCardForKeyboard()
            }
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val semText = binding.spinnerSemester.text.toString()
            val semId = semText.filter { it.isDigit() }.toIntOrNull()
            viewModel.login(email, password, semId)
        }
    }

    private fun toggleSemesterPicker() {
        if (semesterPickerVisible) {
            hideSemesterPicker()
        } else {
            showSemesterPicker()
        }
    }

    private fun showSemesterPicker() {
        semesterPickerVisible = true
        binding.semesterPicker.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            translationY = -18f
            scaleX = 0.94f
            scaleY = 0.94f
            animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .start()
        }

        listOf(
            binding.btnSemester1,
            binding.btnSemester2,
            binding.btnSemester3,
            binding.btnSemester4,
            binding.btnSemester5,
            binding.btnSemester6,
            binding.btnSemester7,
            binding.btnSemester8,
        ).forEachIndexed { index, button ->
            button.animate().cancel()
            button.alpha = 0f
            button.translationY = 18f
            button.scaleX = 0.78f
            button.scaleY = 0.78f
            button.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(index * 24L)
                .setDuration(210L)
                .start()
        }

        binding.loginScroll.postDelayed({
            binding.loginScroll.smoothScrollTo(0, binding.semesterPicker.bottom)
        }, 80L)
    }

    private fun hideSemesterPicker() {
        if (!semesterPickerVisible && binding.semesterPicker.visibility != View.VISIBLE) return
        semesterPickerVisible = false
        binding.semesterPicker.animate().cancel()
        binding.semesterPicker.animate()
            .alpha(0f)
            .translationY(-12f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(150L)
            .withEndAction {
                if (!semesterPickerVisible) {
                    binding.semesterPicker.visibility = View.GONE
                    binding.semesterPicker.translationY = -18f
                }
            }
            .start()
    }

    private fun hideKeyboard() {
        currentFocus?.let { focusedView ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }
    }

    private fun liftLoginCardForKeyboard() {
        val topInset = (42f * resources.displayMetrics.density).toInt()
        binding.loginScroll.postDelayed({
            binding.loginScroll.smoothScrollTo(0, (binding.cardLogin.top - topInset).coerceAtLeast(0))
        }, 180L)
    }

    private fun moveLoginContentForKeyboard(raised: Boolean) {
        val targetY = if (raised) -120f * resources.displayMetrics.density else 0f
        binding.loginContent.animate()
            .translationY(targetY)
            .setDuration(220L)
            .start()
    }
}
