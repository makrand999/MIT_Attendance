package com.mit.attendance.ui.subjects

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.*
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivitySubjectsBinding
import com.mit.attendance.databinding.ActivityErpWebviewBinding
import com.mit.attendance.databinding.DialogShareQrBinding
import com.mit.attendance.databinding.ItemHomeToolBinding
import com.mit.attendance.databinding.ItemSubjectBinding
import com.mit.attendance.databinding.PageHomeOverviewBinding
import com.mit.attendance.databinding.PageHomeToolsBinding
import com.mit.attendance.model.SingleLiveEvent
import com.mit.attendance.model.SubjectUiModel
import com.mit.attendance.ui.chat.ChatActivity
import com.mit.attendance.ui.detail.AttendanceDetailActivity
import com.mit.attendance.ui.login.LoginActivity
import com.mit.attendance.ui.practical.PracticalActivity
import com.mit.attendance.ui.reviews.ReviewsActivity
import com.mit.attendance.ui.timetable.TimetableActivity
import com.mit.attendance.data.api.UpdateChecker
import com.mit.attendance.service.AttendanceSyncWorker
import com.mit.attendance.ui.certifications.CertificationsActivity
import com.mit.attendance.ui.agent.AgentActivity
import com.mit.attendance.ui.alerts.AlertsActivity
import com.mit.attendance.service.AlertSyncWorker
import com.mit.attendance.data.api.HttpClientHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun logout() {
        Log.d(TAG, "Logging out user")
        viewModelScope.launch { repo.logout() }
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
    private val appUrl = "https://github.com/makrand999/MIT_Attendance/releases/latest"

    private lateinit var binding: ActivitySubjectsBinding
    private val viewModel: SubjectsViewModel by viewModels { SubjectsViewModelFactory(applicationContext) }
    private lateinit var homePagerAdapter: SubjectsHomePagerAdapter
    private lateinit var toolsAdapter: HomeToolAdapter
    private lateinit var universalGestureDetector: GestureDetector

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
        setupNavigationDrawer()
        setupUniversalSwipe()
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
                // Update navigation menu if needed
                val navMenu = binding.navView.menu
                navMenu.findItem(R.id.action_notifications)?.title =
                    if (notificationsOn) "Notifications: ON" else "Notifications: OFF"
                renderOverviewPage()
            }
        }

        lifecycleScope.launch {
            viewModel.isUpdateLocked.collect { isLocked ->
                val navMenu = binding.navView.menu
                navMenu.findItem(R.id.action_practical)?.isVisible = isLocked
                navMenu.findItem(R.id.action_lock)?.title = if (isLocked) "Unlock" else "Lock"
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
        if (::universalGestureDetector.isInitialized) {
            universalGestureDetector.onTouchEvent(ev)
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

        TabLayoutMediator(binding.homeTabs, binding.homePager) { tab, _ ->
            tab.text = ""
        }.attach()

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
        pageBinding.btnQuickAgent.setOnClickListener { openHomeAction(HomeAction.AGENT) }
    }

    private fun configureToolsPage(pageBinding: PageHomeToolsBinding) {
        pageBinding.recyclerTools.layoutManager = GridLayoutManager(this, 2)
        pageBinding.recyclerTools.adapter = toolsAdapter
    }

    private fun buildToolItems(): List<HomeToolItem> {
        return listOf(
            HomeToolItem(HomeAction.ALERTS, R.drawable.ic_alert, "Alerts", "Reminders"),
            HomeToolItem(HomeAction.PRACTICALS, R.drawable.ic_code, "Practicals", "Submissions"),
            HomeToolItem(HomeAction.TIMETABLE, R.drawable.ic_dashboard, "Timetable", "Schedule"),
            HomeToolItem(HomeAction.NOTES, R.drawable.ic_folder, "Notes", "Files"),
            HomeToolItem(HomeAction.EDITOR, R.drawable.ic_cut, "Editor", "Crop"),
            HomeToolItem(HomeAction.AGENT, R.drawable.ic_terminal, "Agent", "Assistant"),
            HomeToolItem(HomeAction.CHAT, R.drawable.ic_gpt, "Chat", "Messages"),
            HomeToolItem(HomeAction.CERTIFICATIONS, R.drawable.ic_cloud, "Certificates", "Awards"),
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

    private fun setupUniversalSwipe() {
        universalGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                val swipeThreshold = 100
                val swipeVelocityThreshold = 100
                val drawerEdgeThreshold = 32f * resources.displayMetrics.density

                if (
                    abs(diffX) > abs(diffY) &&
                    diffX > swipeThreshold &&
                    abs(velocityX) > swipeVelocityThreshold &&
                    e1.x <= drawerEdgeThreshold
                ) {
                    if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        binding.drawerLayout.openDrawer(GravityCompat.START)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupNavigationDrawer() {
        Log.d(TAG, "Setting up Navigation Drawer")
        // Set drawer width to 70% as requested
        val displayMetrics = resources.displayMetrics
        val params = binding.navView.layoutParams
        params.width = (displayMetrics.widthPixels * 0.7).toInt()
        binding.navView.layoutParams = params

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Blur effect: decrease alpha/scale of main content as drawer opens
                val alpha = 1f - (slideOffset * 0.6f) // Fade to 40% opacity
                binding.mainContent.alpha = alpha
                binding.mainContent.scaleX = 1f - (slideOffset * 0.05f) // Slight shrink
                binding.mainContent.scaleY = 1f - (slideOffset * 0.05f)

                // Real Blur for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blurRadius = slideOffset * 30f // Max blur 30px
                    if (blurRadius > 0.1f) {
                        binding.mainContent.setRenderEffect(
                            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                        )
                    } else {
                        binding.mainContent.setRenderEffect(null)
                    }
                }
            }
        })

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            Log.d(TAG, "Navigation item selected: ${menuItem.title}")
            if (menuItem.itemId == R.id.action_share) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                binding.root.postDelayed({
                    showShareQrDialog()
                }, 200)
            } else if (menuItem.itemId == R.id.action_update) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                binding.root.postDelayed({
                    lifecycleScope.launch {
                        UpdateChecker.checkForUpdates(this@SubjectsActivity, true)
                    }
                }, 300)
            } else {
                handleMenuItem(menuItem)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            true
        }

        // Setup Toolbar Navigation Icon
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.medkit_line))
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun handleMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_erp -> {
                openHomeAction(HomeAction.ERP)
                true
            }
            R.id.action_exam_cell -> {
                openHomeAction(HomeAction.EXAM_CELL)
                true
            }
            R.id.action_agent -> {
                openHomeAction(HomeAction.AGENT)
                true
            }
            R.id.action_notes -> {
                openHomeAction(HomeAction.NOTES)
                true
            }
            R.id.action_timetable -> {
                openHomeAction(HomeAction.TIMETABLE)
                true
            }
            R.id.action_chat -> {
                openHomeAction(HomeAction.CHAT)
                true
            }
            R.id.action_alerts -> {
                openHomeAction(HomeAction.ALERTS)
                true
            }
            R.id.action_practical -> {
                openHomeAction(HomeAction.PRACTICALS)
                true
            }
            R.id.action_reviews -> {
                openHomeAction(HomeAction.REVIEWS)
                true
            }
            R.id.action_certifications -> {
                openHomeAction(HomeAction.CERTIFICATIONS)
                true
            }
            R.id.action_notifications -> {
                handleNotificationToggle()
                true
            }
            R.id.action_customize -> {
                openHomeAction(HomeAction.CUSTOMIZE)
                true
            }
            R.id.action_lock -> {
                showLockPasswordDialog()
                true
            }
            R.id.action_update -> {
                // Handled in setNavigationItemSelectedListener for smoother closing
                true
            }
            R.id.action_logout -> {
                viewModel.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> false
        }
    }

    private fun openHomeAction(action: HomeAction) {
        when (action) {
            HomeAction.ERP -> openErpWebView()
            HomeAction.EXAM_CELL -> openExamCellWebView()
            HomeAction.AGENT -> startActivity(Intent(this, AgentActivity::class.java))
            HomeAction.NOTES -> {
                val intent = Intent(this, NotesActivity::class.java).apply {
                    putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_NOTES)
                }
                startActivity(intent)
            }
            HomeAction.EDITOR -> {
                val intent = Intent(this, NotesActivity::class.java).apply {
                    putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_EDITOR)
                }
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
            HomeAction.CERTIFICATIONS -> startActivity(Intent(this, CertificationsActivity::class.java))
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showShareQrDialog() {
        val dialogBinding = DialogShareQrBinding.inflate(layoutInflater)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogBinding.root)
            .create()

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                generateQrCodeBitmap(appUrl, Color.WHITE, Color.TRANSPARENT)
            }
            dialogBinding.ivQrCode.setImageBitmap(bitmap)
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: 0f)
                val dy = e2.y - (e1?.y ?: 0f)

                if (abs(dy) > abs(dx) && dy > 100) {
                    // Swipe Down: Animation + Generate QR from clipboard
                    dialogBinding.ivQrCode.animate()
                        .translationY(60f)
                        .setDuration(200)
                        .withEndAction {
                            dialogBinding.ivQrCode.animate().translationY(0f).setDuration(200).start()
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val pasteText = clip.getItemAt(0).text.toString()
                                lifecycleScope.launch {
                                    val bitmap = withContext(Dispatchers.Default) {
                                        generateQrCodeBitmap(pasteText, Color.WHITE, Color.TRANSPARENT)
                                    }
                                    dialogBinding.ivQrCode.setImageBitmap(bitmap)
                                }
                            }
                        }.start()
                    return true
                } else if (abs(dx) > abs(dy) && dx > 100) {
                    // Swipe Right: Disappear to right + Copy app URL
                    dialogBinding.ivQrCode.animate()
                        .translationX(1200f)
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction {
                            dialog.dismiss()
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("App Link", appUrl)
                            clipboard.setPrimaryClip(clip)
                        }.start()
                    return true
                }
                return false
            }
        })

        dialogBinding.ivQrCode.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        dialog.show()
    }

    private fun generateQrCodeBitmap(text: String, foreground: Int = Color.BLACK, background: Int = Color.WHITE): Bitmap? {
        try {
            val bitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) foreground else background)
                }
            }
            return bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            return null
        }
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
    ALERTS,
    PRACTICALS,
    TIMETABLE,
    NOTES,
    EDITOR,
    AGENT,
    CHAT,
    CERTIFICATIONS,
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
