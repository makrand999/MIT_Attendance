package com.mit.attendance.ui.subjects

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.*
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.*
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivitySubjectsBinding
import com.mit.attendance.databinding.ActivityErpWebviewBinding
import com.mit.attendance.databinding.ItemHomeToolBinding
import com.mit.attendance.databinding.ItemSubjectBinding
import com.mit.attendance.databinding.PageHomeOverviewBinding
import com.mit.attendance.databinding.PageHomeToolsBinding
import com.mit.attendance.model.SingleLiveEvent
import com.mit.attendance.model.SubjectUiModel
import com.mit.attendance.ui.chat.ChatActivity
import com.mit.attendance.ui.detail.AttendanceDetailActivity
import com.mit.attendance.ui.practical.PracticalActivity
import com.mit.attendance.ui.reviews.ReviewsActivity
import com.mit.attendance.ui.timetable.TimetableActivity
import com.mit.attendance.data.api.UpdateChecker
import com.mit.attendance.service.AttendanceSyncWorker
import com.mit.attendance.ui.alerts.AlertsActivity
import com.mit.attendance.service.AlertSyncWorker
import com.mit.attendance.data.api.HttpClientHolder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SubjectsViewModel(private val repo: AttendanceRepository) : ViewModel() {

    private val TAG = "SubjectsViewModel"

    val subjects = repo.getSubjectsFlow()
    val notificationsEnabled = repo.prefs.notificationsEnabled
    val hasRequestedNotifPermission = repo.prefs.hasRequestedNotifPermission
    val isUpdateLocked = repo.prefs.isUpdateLocked
    val bgDetailUri = repo.prefs.bgDetailUri
    val gender = repo.getGenderFlow()

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    private val _error = SingleLiveEvent<String>()
    val error: LiveData<String> = _error

    init {
        Log.d(TAG, "ViewModel initialized, starting refresh")
        refresh()
    }

    fun refresh() {
        if (_isRefreshing.value == true) return
        viewModelScope.launch {
            _isRefreshing.value = true
            Log.d(TAG, "Starting refresh sync...")
            try {
                repo.initialSync()
                Log.d(TAG, "Sync completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                _error.call("Failed to refresh. Check your connection.")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleNotifications(enabled: Boolean) {
        Log.d(TAG, "Toggling notifications: $enabled")
        viewModelScope.launch { repo.prefs.setNotificationsEnabled(enabled) }
    }

    fun setHasRequestedNotifPermission(requested: Boolean) {
        viewModelScope.launch { repo.prefs.setHasRequestedNotifPermission(requested) }
    }

    fun setUpdateLocked(locked: Boolean) {
        viewModelScope.launch { repo.prefs.setUpdateLocked(locked) }
    }

}

class SubjectsViewModelFactory(private val context: android.content.Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SubjectsViewModel(AttendanceRepository(context)) as T
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class SubjectsActivity : AppCompatActivity() {

    private val TAG = "SubjectsActivity"

    private lateinit var binding: ActivitySubjectsBinding
    private val viewModel: SubjectsViewModel by viewModels { SubjectsViewModelFactory(applicationContext) }
    private lateinit var homePagerAdapter: SubjectsHomePagerAdapter
    private lateinit var toolsAdapter: HomeToolAdapter

    private var notificationsOn = true
    private var latestSubjects: List<SubjectUiModel> = emptyList()
    private var latestRefreshing = false
    private var latestGender: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Notification permission result: $isGranted")
        viewModel.setHasRequestedNotifPermission(true)
        if (isGranted) {
            viewModel.toggleNotifications(true)
            AttendanceSyncWorker.schedule(applicationContext)
            AlertSyncWorker.schedule(applicationContext)
            Snackbar.make(binding.root, "Notifications enabled", Snackbar.LENGTH_SHORT).show()
        } else {
            viewModel.toggleNotifications(false)
            Snackbar.make(binding.root, "Permission denied. Notifications disabled.", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        binding = ActivitySubjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Attendance Desk"

        setupHomePager()
        observeData()

        // Initialize WebView Window Manager
        WebViewWindowManager.init(this)

        AlertSyncWorker.runNow(this)

        lifecycleScope.launch {
            val isLocked = viewModel.isUpdateLocked.first()
            if (!isLocked) {
                Log.d(TAG, "Checking for updates...")
                UpdateChecker.checkForUpdates(this@SubjectsActivity)
            } else {
                Log.d(TAG, "Update check skipped: Locked")
            }
        }

        lifecycleScope.launch {
            viewModel.notificationsEnabled.collect { enabled ->
                Log.d(TAG, "Notifications flow collected: $enabled")
                notificationsOn = enabled
                if (enabled && !isNotificationPermissionGranted()) {
                    Log.d(TAG, "Notifications enabled but permission not granted, turning off")
                    notificationsOn = false
                    viewModel.toggleNotifications(false)
                }
                renderOverviewPage()
            }
        }

        lifecycleScope.launch {
            viewModel.bgDetailUri.collect { uri ->
                if (uri != null) {
                    binding.ivBackground.setImageURI(uri.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_medkit_screen)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.gender.collect { gender ->
                latestGender = gender
                updateOverviewAvatar()
            }
        }

        // Auto-request permission once on first launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                val hasRequested = viewModel.hasRequestedNotifPermission.first()
                Log.d(TAG, "Notification permission check - hasRequested: $hasRequested")
                if (!hasRequested && !isNotificationPermissionGranted()) {
                    Log.d(TAG, "Requesting notification permission for the first time")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (WebViewWindowManager.handleTouchEvent(ev, binding.webViewContainer)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun setupHomePager() {
        toolsAdapter = HomeToolAdapter(buildToolItems(), ::openHomeAction)
        homePagerAdapter = SubjectsHomePagerAdapter(
            onOverviewReady = { pageBinding ->
                configureOverviewPage(pageBinding)
                renderOverviewPage()
                updateRefreshingState(latestRefreshing)
            },
            onToolsReady = { pageBinding ->
                configureToolsPage(pageBinding)
            }
        )

        binding.homePager.adapter = homePagerAdapter
        binding.homePager.offscreenPageLimit = 2

        val pageOffset = 20f * resources.displayMetrics.density
        binding.homePager.setPageTransformer { page, position ->
            page.translationX = -position * (pageOffset / 2f)
            page.alpha = 0.85f + ((1f - abs(position.coerceIn(-1f, 1f))) * 0.15f)
        }

        binding.homePager.setCurrentItem(0, false)
    }

    private fun configureOverviewPage(pageBinding: PageHomeOverviewBinding) {
        pageBinding.overviewSwipeRefresh.setColorSchemeResources(
            R.color.medkit_peach_deep,
            R.color.medkit_mint_deep,
            R.color.medkit_sky_deep
        )
        pageBinding.overviewSwipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.medkit_paper)
        )
        pageBinding.overviewSwipeRefresh.setOnRefreshListener { viewModel.refresh() }

        val openOverallAttendance = {
            startActivity(Intent(this, AttendanceSubjectsActivity::class.java))
        }
        pageBinding.cardOverviewHero.setOnClickListener { openOverallAttendance() }
        pageBinding.cardSubjectCount.setOnClickListener { openOverallAttendance() }
        pageBinding.tvOverviewHeroCaption.text = "Tap for subjects."
        updateOverviewAvatar()

        pageBinding.btnQuickAlerts.setOnClickListener { openHomeAction(HomeAction.ALERTS) }
        pageBinding.btnQuickPracticals.setOnClickListener { openHomeAction(HomeAction.PRACTICALS) }
        pageBinding.btnQuickTimetable.setOnClickListener { openHomeAction(HomeAction.TIMETABLE) }
        pageBinding.btnQuickNotes.setOnClickListener { openHomeAction(HomeAction.NOTES) }
    }

    private fun configureToolsPage(pageBinding: PageHomeToolsBinding) {
        pageBinding.recyclerTools.layoutManager = GridLayoutManager(this, 2)
        pageBinding.recyclerTools.adapter = toolsAdapter
    }

    private fun buildToolItems(): List<HomeToolItem> {
        return listOf(
            HomeToolItem(HomeAction.CAMPUS, R.drawable.ic_school, "Akatsuki", "Opportunities & chat"),
            HomeToolItem(HomeAction.ALERTS, R.drawable.ic_alert, "Alerts", "Reminders"),
            HomeToolItem(HomeAction.PRACTICALS, R.drawable.ic_code, "Practicals", "Submissions"),
            HomeToolItem(HomeAction.TIMETABLE, R.drawable.ic_dashboard, "Timetable", "Schedule"),
            HomeToolItem(HomeAction.NOTES, R.drawable.ic_folder, "Notes", "Files"),
            HomeToolItem(HomeAction.CHAT, R.drawable.ic_gpt, "Chat", "Messages"),
            HomeToolItem(HomeAction.ERP, R.drawable.ic_browser, "ERP", "Portal"),
            HomeToolItem(HomeAction.EXAM_CELL, R.drawable.ic_school, "Exam Cell", "Exams"),
            HomeToolItem(HomeAction.REVIEWS, R.drawable.ic_devices, "Reviews", "Ratings"),
            HomeToolItem(HomeAction.CUSTOMIZE, R.drawable.ic_refresh, "Customize", "Theme")
        )
    }

    private fun renderOverviewPage() {
        val pageBinding = homePagerAdapter.overviewBinding ?: return
        val subjects = latestSubjects
        updateOverviewAvatar()

        if (subjects.isEmpty()) {
            val neutral = ContextCompat.getColor(this, R.color.medkit_ink)
            pageBinding.tvOverviewHeroValue.text = "--"
            pageBinding.tvOverviewHeroValue.setTextColor(neutral)
            pageBinding.cardOverviewHero.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
            pageBinding.cardOverviewHero.setCardBackgroundColor(ContextCompat.getColor(this, R.color.medkit_peach))
            pageBinding.tvOverviewHeroCaption.text = "Pull to refresh."
            pageBinding.tvTotalPresent.text = "0"
            pageBinding.tvTotalLectures.text = "0"
            pageBinding.tvSubjectCount.text = "0"
            pageBinding.tvSubjectCountCaption.text = "Tracked"
            pageBinding.tvRiskCount.text = "0"
            pageBinding.tvRiskCount.setTextColor(neutral)
            pageBinding.cardSubjectCount.setCardBackgroundColor(ContextCompat.getColor(this, R.color.medkit_sky))
            pageBinding.cardRiskCount.setCardBackgroundColor(ContextCompat.getColor(this, R.color.medkit_paper))
            pageBinding.cardRiskCount.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
            pageBinding.tvSafeCount.text = "0"
            pageBinding.cardSafeCount.setCardBackgroundColor(ContextCompat.getColor(this, R.color.medkit_mint))
            pageBinding.cardSafeCount.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
            pageBinding.tvNotificationsState.text = if (notificationsOn) "ON" else "OFF"
            pageBinding.tvNotificationsState.setTextColor(neutral)
            pageBinding.cardNotifications.setCardBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (notificationsOn) R.color.medkit_butter else R.color.medkit_paper
                )
            )
            pageBinding.cardNotifications.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
            pageBinding.tvNotificationsCaption.text = "Updates"
            return
        }

        val totalPresent = subjects.sumOf { it.present }
        val totalClasses = subjects.sumOf { it.total }
        val overallPct = if (totalClasses > 0) (totalPresent * 100f / totalClasses) else 0f
        val belowTargetCount = subjects.count { it.percentage < 75f }
        val safeCount = subjects.count { it.percentage >= 75f }
        val overviewColor = when {
            overallPct >= 75f -> ContextCompat.getColor(this, R.color.medkit_mint_deep)
            overallPct >= 60f -> ContextCompat.getColor(this, R.color.medkit_butter_deep)
            else -> ContextCompat.getColor(this, R.color.medkit_rose_deep)
        }
        val riskColor = if (belowTargetCount > 0) {
            ContextCompat.getColor(this, R.color.medkit_rose_deep)
        } else {
            ContextCompat.getColor(this, R.color.medkit_line)
        }
        val notificationColor = if (notificationsOn) {
            ContextCompat.getColor(this, R.color.medkit_mint_deep)
        } else {
            ContextCompat.getColor(this, R.color.medkit_line)
        }

        pageBinding.tvOverviewHeroValue.text = "${overallPct.toInt()}%"
        pageBinding.tvOverviewHeroValue.setTextColor(overviewColor)
        pageBinding.cardOverviewHero.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
        pageBinding.cardOverviewHero.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                when {
                    overallPct >= 75f -> R.color.medkit_mint
                    overallPct >= 60f -> R.color.medkit_butter
                    else -> R.color.medkit_peach
                }
            )
        )
        pageBinding.tvTotalPresent.text = totalPresent.toString()
        pageBinding.tvTotalLectures.text = totalClasses.toString()
        pageBinding.tvOverviewHeroCaption.text =
            if (belowTargetCount == 0) {
                "All subjects are on track."
            } else {
                "$belowTargetCount need attention."
            }

        pageBinding.tvSubjectCount.text = subjects.size.toString()
        pageBinding.tvSubjectCountCaption.text = "$totalClasses classes"
        pageBinding.cardSubjectCount.setCardBackgroundColor(ContextCompat.getColor(this, R.color.medkit_sky))
        pageBinding.cardSubjectCount.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)

        pageBinding.tvRiskCount.text = belowTargetCount.toString()
        pageBinding.tvRiskCount.setTextColor(riskColor)
        pageBinding.cardRiskCount.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (belowTargetCount > 0) R.color.medkit_rose else R.color.medkit_paper
            )
        )
        pageBinding.cardRiskCount.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
        pageBinding.tvRiskCountCaption.text = "Below 75%"

        pageBinding.tvSafeCount.text = safeCount.toString()
        pageBinding.cardSafeCount.setCardBackgroundColor(ContextCompat.getColor(this, R.color.medkit_mint))
        pageBinding.cardSafeCount.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
        pageBinding.tvSafeCountCaption.text = "Ready"

        pageBinding.tvNotificationsState.text = if (notificationsOn) "ON" else "OFF"
        pageBinding.tvNotificationsState.setTextColor(notificationColor)
        pageBinding.cardNotifications.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (notificationsOn) R.color.medkit_butter else R.color.medkit_paper
            )
        )
        pageBinding.cardNotifications.strokeColor = ContextCompat.getColor(this, R.color.medkit_line)
        pageBinding.tvNotificationsCaption.text =
            if (notificationsOn) "Updates on" else "Updates off"
    }

    private fun updateOverviewAvatar() {
        val pageBinding = homePagerAdapter.overviewBinding ?: return
        val avatarRes = if (isFemaleGender(latestGender)) {
            R.drawable.ic_avatar_girl
        } else {
            R.drawable.ic_avatar_boy
        }
        pageBinding.ivOverviewAvatar.setImageResource(avatarRes)
    }

    private fun isFemaleGender(gender: String?): Boolean {
        val normalized = gender?.trim()?.lowercase() ?: return false
        return normalized == "f" ||
            normalized == "female" ||
            normalized == "girl" ||
            normalized.startsWith("fem")
    }

    private fun updateRefreshingState(refreshing: Boolean) {
        latestRefreshing = refreshing
        binding.toolbarProgressBar.visibility = if (refreshing) View.VISIBLE else View.GONE
        homePagerAdapter.overviewBinding?.overviewSwipeRefresh?.isRefreshing = refreshing
    }

    private fun openHomeAction(action: HomeAction) {
        when (action) {
            HomeAction.CAMPUS -> startActivity(Intent(this, com.mit.attendance.campus.CampusActivity::class.java))
            HomeAction.ERP -> openErpWebView()
            HomeAction.EXAM_CELL -> openExamCellWebView()
            HomeAction.NOTES -> {
                val intent = Intent(this, NotesLibraryActivity::class.java)
                startActivity(intent)
            }
            HomeAction.TIMETABLE -> startActivity(Intent(this, TimetableActivity::class.java))
            HomeAction.CHAT -> startActivity(Intent(this, ChatActivity::class.java))
            HomeAction.ALERTS -> startActivity(Intent(this, AlertsActivity::class.java))
            HomeAction.PRACTICALS -> lifecycleScope.launch {
                if (viewModel.isUpdateLocked.first()) {
                    startActivity(Intent(this@SubjectsActivity, PracticalActivity::class.java))
                } else {
                    showLockPasswordDialog()
                }
            }
            HomeAction.REVIEWS -> startActivity(Intent(this, ReviewsActivity::class.java))
            HomeAction.CUSTOMIZE -> startActivity(Intent(this, CustomizeActivity::class.java))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openErpWebView() {
        val erpBinding = ActivityErpWebviewBinding.inflate(layoutInflater, binding.webViewContainer, false)
        erpBinding.btnBack.visibility = View.VISIBLE
        erpBinding.btnBack.setOnClickListener {
            binding.webViewContainer.removeView(erpBinding.root)
            WebViewWindowManager.onViewRemoved(erpBinding.root)
        }
        erpBinding.toolbar.visibility = View.GONE

        val url = "https://erp.mit.asia/studentCourseFileNew.htm?shwA=%2700A%27"
        val sessionId = HttpClientHolder.getSessionId()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(erpBinding.webView, true)

        if (sessionId != null) {
            val cookieString = "JSESSIONID=$sessionId; Domain=erp.mit.asia; Path=/; Secure; HttpOnly"
            cookieManager.setCookie("https://erp.mit.asia", cookieString)
        }
        cookieManager.flush()

        erpBinding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        erpBinding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                erpBinding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                erpBinding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        erpBinding.webView.loadUrl(url)
        WebViewWindowManager.addWebView(binding.webViewContainer, erpBinding.root)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun openExamCellWebView() {
        val examBinding = ActivityErpWebviewBinding.inflate(layoutInflater, binding.webViewContainer, false)
        examBinding.btnBack.visibility = View.VISIBLE
        examBinding.btnBack.setOnClickListener {
            binding.webViewContainer.removeView(examBinding.root)
            WebViewWindowManager.onViewRemoved(examBinding.root)
        }
        examBinding.toolbar.visibility = View.GONE

        val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

        examBinding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgent
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(examBinding.webView, true)

        examBinding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                examBinding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                examBinding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        examBinding.webView.loadUrl("https://mitaexamcell.in/Login.aspx")
        WebViewWindowManager.addWebView(binding.webViewContainer, examBinding.root)
    }

    private fun showLockPasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lock, null)
        val etPassword = view.findViewById<TextInputEditText>(R.id.etPassword)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)
        val btnLock = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLock)

        lifecycleScope.launch {
            val isCurrentlyLocked = viewModel.isUpdateLocked.first()
            btnLock.text = if (isCurrentlyLocked) "Unlock" else "Lock"

            val dialog = AlertDialog.Builder(this@SubjectsActivity, R.style.TransparentDialog)
                .setView(view)
                .create()

            btnCancel.setOnClickListener { dialog.dismiss() }
            btnLock.setOnClickListener {
                val password = etPassword.text.toString()
                if (password == "fang yuan") {
                    viewModel.setUpdateLocked(!isCurrentlyLocked)
                    dialog.dismiss()
                } else {
                    etPassword.error = "Incorrect phrase"
                }
            }

            dialog.show()
        }
    }

    private fun handleNotificationToggle() {
        val newEnabled = !notificationsOn
        Log.d(TAG, "Handling notification toggle to: $newEnabled")
        if (newEnabled) {
            if (isNotificationPermissionGranted()) {
                enableNotifications()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permission = Manifest.permission.POST_NOTIFICATIONS
                when {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                        showPermissionRationaleDialog()
                    }
                    else -> {
                        lifecycleScope.launch {
                            if (viewModel.hasRequestedNotifPermission.first()) {
                                showSettingsRedirectDialog()
                            } else {
                                requestPermissionLauncher.launch(permission)
                            }
                        }
                    }
                }
            } else {
                enableNotifications()
            }
        } else {
            disableNotifications()
        }
    }

    private fun enableNotifications() {
        Log.d(TAG, "Enabling notifications")
        viewModel.toggleNotifications(true)
        AttendanceSyncWorker.schedule(applicationContext)
        AlertSyncWorker.schedule(applicationContext)
        renderOverviewPage()
        Snackbar.make(binding.root, "Notifications enabled", Snackbar.LENGTH_SHORT).show()
    }

    private fun disableNotifications() {
        Log.d(TAG, "Disabling notifications")
        viewModel.toggleNotifications(false)
        AttendanceSyncWorker.cancel(applicationContext)
        AlertSyncWorker.cancel(applicationContext)
        renderOverviewPage()
        Snackbar.make(binding.root, "Notifications disabled", Snackbar.LENGTH_SHORT).show()
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Permission")
            .setMessage("Notifications help you stay updated with your attendance changes. Please allow the permission.")
            .setPositiveButton("Allow") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsRedirectDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Notification permission has been denied. Please enable it in Settings to receive updates.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.subjects.collectLatest { subjects ->
                Log.d(TAG, "Observed ${subjects.size} subjects")
                latestSubjects = subjects
                renderOverviewPage()
            }
        }
        viewModel.isRefreshing.observe(this) { refreshing ->
            Log.d(TAG, "Refreshing state: $refreshing")
            updateRefreshingState(refreshing)
        }
        viewModel.error.observe(this) { msg ->
            Log.e(TAG, "Error observed: $msg")
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class SubjectAdapter(
    private val onClick: (SubjectUiModel) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.VH>() {

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<SubjectUiModel>() {
        override fun areItemsTheSame(o: SubjectUiModel, n: SubjectUiModel) = o.id == n.id
        override fun areContentsTheSame(o: SubjectUiModel, n: SubjectUiModel) = o == n
    })

    fun submitList(newList: List<SubjectUiModel>) = differ.submitList(newList)
    fun submitList(newList: List<SubjectUiModel>, commitCallback: Runnable) =
        differ.submitList(newList, commitCallback)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val subject = differ.currentList[position]
        holder.binding.apply {
            tvSubjectName.text = subject.name
            tvPercentage.text = "${subject.percentage.toInt()}%"
            tvStats.text = "${subject.present}/${subject.total} attended"
            tvStats.visibility = View.VISIBLE
            cardView.rotation = when (position % 4) {
                0 -> -0.3f
                1 -> 0.3f
                2 -> -0.15f
                else -> 0.15f
            }
            progressPanel.rotation = when (position % 6) {
                0 -> -1.6f
                1 -> 1.25f
                2 -> -0.8f
                3 -> 1.7f
                4 -> 0.7f
                else -> -1.25f
            }
            progressPanel.translationX = when (position % 4) {
                0 -> -3f
                1 -> 4f
                2 -> 2f
                else -> -4f
            }

            val color = when {
                subject.percentage >= 75 -> ContextCompat.getColor(root.context, R.color.medkit_mint_deep)
                subject.percentage >= 60 -> ContextCompat.getColor(root.context, R.color.medkit_butter_deep)
                else -> ContextCompat.getColor(root.context, R.color.medkit_rose_deep)
            }
            tvPercentage.setTextColor(color)
            progressBar.progress = subject.percentage.toInt()
            progressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

            if (subject.hasNewEntries) {
                tvSubjectName.setTextColor(ContextCompat.getColor(root.context, R.color.medkit_ink))
                tvSubjectName.typeface = android.graphics.Typeface.DEFAULT_BOLD
                cardView.setCardBackgroundColor(ContextCompat.getColor(root.context, R.color.medkit_peach))
                cardView.strokeColor = ContextCompat.getColor(root.context, R.color.medkit_line)
                badgeNew.visibility = View.VISIBLE
            } else {
                tvSubjectName.setTextColor(ContextCompat.getColor(root.context, R.color.medkit_ink))
                tvSubjectName.typeface = android.graphics.Typeface.DEFAULT_BOLD
                val backgroundRes = when {
                    subject.percentage >= 75 -> R.color.medkit_mint
                    subject.percentage >= 60 -> R.color.medkit_butter
                    else -> R.color.medkit_rose
                }
                cardView.setCardBackgroundColor(ContextCompat.getColor(root.context, backgroundRes))
                cardView.strokeColor = ContextCompat.getColor(root.context, R.color.medkit_line)
                badgeNew.visibility = View.GONE
            }
            cardView.strokeWidth = (root.context.resources.displayMetrics.density * 2).toInt().coerceAtLeast(2)

            root.setOnClickListener { onClick(subject) }
        }
    }

    override fun getItemCount() = differ.currentList.size

    class VH(val binding: ItemSubjectBinding) : RecyclerView.ViewHolder(binding.root)
}

private enum class HomeAction {
    CAMPUS,
    ALERTS,
    PRACTICALS,
    TIMETABLE,
    NOTES,
    CHAT,
    ERP,
    EXAM_CELL,
    REVIEWS,
    CUSTOMIZE
}

private data class HomeToolItem(
    val action: HomeAction,
    val iconRes: Int,
    val title: String,
    val subtitle: String
)

private class HomeToolAdapter(
    private val items: List<HomeToolItem>,
    private val onClick: (HomeAction) -> Unit
) : RecyclerView.Adapter<HomeToolAdapter.ToolViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolViewHolder {
        val binding = ItemHomeToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ToolViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ToolViewHolder, position: Int) {
        val item = items[position]
        val cardColors = listOf(
            R.color.medkit_peach,
            R.color.medkit_mint,
            R.color.medkit_sky,
            R.color.medkit_butter,
            R.color.medkit_rose
        )
        holder.binding.ivIcon.setImageResource(item.iconRes)
        holder.binding.tvTitle.text = item.title
        holder.binding.tvSubtitle.text = item.subtitle
        holder.binding.root.setCardBackgroundColor(
            ContextCompat.getColor(holder.binding.root.context, cardColors[position % cardColors.size])
        )
        holder.binding.root.rotation = when (position % 6) {
            0 -> -1.4f
            1 -> 1.0f
            2 -> 0.65f
            3 -> -1.1f
            4 -> -0.55f
            else -> 1.35f
        }
        holder.binding.root.translationY = when (position % 4) {
            0 -> 0f
            1 -> 14f
            2 -> -8f
            else -> 10f
        }
        holder.binding.root.translationX = when (position % 4) {
            0 -> -4f
            1 -> 5f
            2 -> 3f
            else -> -5f
        }
        holder.binding.root.strokeColor =
            ContextCompat.getColor(holder.binding.root.context, R.color.medkit_line)
        holder.binding.root.setOnClickListener { onClick(item.action) }
    }

    override fun getItemCount(): Int = items.size

    class ToolViewHolder(val binding: ItemHomeToolBinding) : RecyclerView.ViewHolder(binding.root)
}

private class SubjectsHomePagerAdapter(
    private val onOverviewReady: (PageHomeOverviewBinding) -> Unit,
    private val onToolsReady: (PageHomeToolsBinding) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var overviewBinding: PageHomeOverviewBinding? = null
        private set

    override fun getItemCount(): Int = 2

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val binding = PageHomeOverviewBinding.inflate(inflater, parent, false)
                overviewBinding = binding
                onOverviewReady(binding)
                OverviewViewHolder(binding)
            }
            else -> {
                val binding = PageHomeToolsBinding.inflate(inflater, parent, false)
                onToolsReady(binding)
                ToolsViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

    class OverviewViewHolder(binding: PageHomeOverviewBinding) : RecyclerView.ViewHolder(binding.root)
    class ToolsViewHolder(binding: PageHomeToolsBinding) : RecyclerView.ViewHolder(binding.root)
}
