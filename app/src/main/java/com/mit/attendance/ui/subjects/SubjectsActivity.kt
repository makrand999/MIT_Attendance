package com.mit.attendance.ui.subjects

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.*
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
import android.widget.EditText
import android.widget.ImageView
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivitySubjectsBinding
import com.mit.attendance.databinding.ItemSubjectBinding
import com.mit.attendance.databinding.DialogShareQrBinding
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
    private lateinit var adapter: SubjectAdapter

    private var notificationsOn = true

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Notification permission result: $isGranted")
        viewModel.setHasRequestedNotifPermission(true)
        if (isGranted) {
            viewModel.toggleNotifications(true)
            AttendanceSyncWorker.schedule(applicationContext)
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
        supportActionBar?.title = "My Attendance"

        setupRecyclerView()
        setupSwipeRefresh()
        setupNavigationDrawer()
        observeData()

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
            }
        }

        lifecycleScope.launch {
            viewModel.bgDetailUri.collect { uri ->
                if (uri != null) {
                    binding.ivBackground.setImageURI(uri.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_detail)
                }
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

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
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
            } else {
                handleMenuItem(menuItem)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            true
        }

        // Setup Toolbar Navigation Icon
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun handleMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_agent -> {
                startActivity(Intent(this, AgentActivity::class.java))
                true
            }
            R.id.action_timetable -> {
                startActivity(Intent(this, TimetableActivity::class.java))
                true
            }
            R.id.action_chat -> {
                startActivity(Intent(this, ChatActivity::class.java))
                true
            }
            R.id.action_erp -> {
                startActivity(Intent(this, ErpWebViewActivity::class.java))
                true
            }
            R.id.action_exam_cell -> {
                startActivity(Intent(this, ExamCellWebViewActivity::class.java))
                true
            }
            R.id.action_practical -> {
                startActivity(Intent(this, PracticalActivity::class.java))
                true
            }
            R.id.action_reviews -> {
                startActivity(Intent(this, ReviewsActivity::class.java))
                true
            }
            R.id.action_certifications -> {
                startActivity(Intent(this, CertificationsActivity::class.java))
                true
            }
            R.id.action_notifications -> {
                handleNotificationToggle()
                true
            }
            R.id.action_customize -> {
                startActivity(Intent(this, CustomizeActivity::class.java))
                true
            }
            R.id.action_lock -> {
                showLockPasswordDialog()
                true
            }
            R.id.action_update -> {
                startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/makrand999/MIT_Attendance"))
                )
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
        val btnLock = view.findViewById<View>(R.id.btnLock)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnLock.setOnClickListener {
            val password = etPassword.text.toString()
            if (password == "fang yuan") {
                viewModel.setUpdateLocked(true)
                dialog.dismiss()
            } else {
                etPassword.error = "Incorrect phrase"
            }
        }

        dialog.show()
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
        Snackbar.make(binding.root, "Notifications enabled", Snackbar.LENGTH_SHORT).show()
    }

    private fun disableNotifications() {
        Log.d(TAG, "Disabling notifications")
        viewModel.toggleNotifications(false)
        AttendanceSyncWorker.cancel(applicationContext)
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

    private fun setupRecyclerView() {
        Log.d(TAG, "Setting up RecyclerView")
        adapter = SubjectAdapter { subject ->
            Log.d(TAG, "Subject clicked: ${subject.name}")
            startActivity(
                Intent(this, AttendanceDetailActivity::class.java).apply {
                    putExtra(AttendanceDetailActivity.EXTRA_SUBJECT_ID, subject.id)
                    putExtra(AttendanceDetailActivity.EXTRA_SUBJECT_NAME, subject.name)
                }
            )
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary, R.color.accent)
        binding.swipeRefresh.setOnRefreshListener {
            Log.d(TAG, "Swipe refresh triggered")
            viewModel.refresh()
            binding.swipeRefresh.isRefreshing = false // Stop animation immediately
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.subjects.collectLatest { subjects ->
                Log.d(TAG, "Observed ${subjects.size} subjects")
                adapter.submitList(subjects)
                binding.tvEmpty.visibility = if (subjects.isEmpty()) View.VISIBLE else View.GONE

                if (subjects.isNotEmpty()) {
                    val totalPresent = subjects.sumOf { it.present }
                    val totalClasses = subjects.sumOf { it.total }
                    val overallPct = if (totalClasses > 0) (totalPresent * 100f / totalClasses) else 0f

                    binding.layoutOverall.visibility = View.VISIBLE
                    binding.tvOverallPercentage.text = "${overallPct.toInt()}%"

                    val color = when {
                        overallPct >= 75 -> ContextCompat.getColor(this@SubjectsActivity, R.color.green)
                        overallPct >= 60 -> ContextCompat.getColor(this@SubjectsActivity, R.color.yellow)
                        else             -> ContextCompat.getColor(this@SubjectsActivity, R.color.red)
                    }
                    binding.tvOverallPercentage.setTextColor(color)
                    binding.progressBarOverall.progress = overallPct.toInt()
                    binding.progressBarOverall.progressTintList =
                        android.content.res.ColorStateList.valueOf(color)
                } else {
                    binding.layoutOverall.visibility = View.GONE
                }
            }
        }
        viewModel.isRefreshing.observe(this) { refreshing ->
            Log.d(TAG, "Refreshing state: $refreshing")
            binding.toolbarProgressBar.visibility = if (refreshing) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val subject = differ.currentList[position]
        holder.binding.apply {
            tvSubjectName.text = subject.name
            tvPercentage.text = "${subject.percentage.toInt()}%"

            val color = when {
                subject.percentage >= 75 -> ContextCompat.getColor(root.context, R.color.green)
                subject.percentage >= 60 -> ContextCompat.getColor(root.context, R.color.yellow)
                else -> ContextCompat.getColor(root.context, R.color.red)
            }
            tvPercentage.setTextColor(color)
            progressBar.progress = subject.percentage.toInt()
            progressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

            // Highlight border if there are new updates
            val density = root.context.resources.displayMetrics.density
            if (subject.hasNewEntries) {
                cardView.strokeColor = ContextCompat.getColor(root.context, R.color.faint_pink_border)
                cardView.strokeWidth = (2.5f * density).toInt()
                badgeNew.visibility = View.GONE
            } else {
                cardView.strokeColor = android.graphics.Color.parseColor("#4DFFFFFF")
                cardView.strokeWidth = (1f * density).toInt()
                badgeNew.visibility = View.GONE
            }

            root.setOnClickListener { onClick(subject) }
        }
    }

    override fun getItemCount() = differ.currentList.size

    class VH(val binding: ItemSubjectBinding) : RecyclerView.ViewHolder(binding.root)
}