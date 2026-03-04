package com.mit.attendance.ui.subjects

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivitySubjectsBinding
import com.mit.attendance.databinding.ItemSubjectBinding
import com.mit.attendance.model.SingleLiveEvent
import com.mit.attendance.model.SubjectUiModel
import com.mit.attendance.service.AttendanceSyncWorker
import com.mit.attendance.ui.detail.AttendanceDetailActivity
import com.mit.attendance.ui.login.LoginActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SubjectsViewModel(private val repo: AttendanceRepository) : ViewModel() {

    val subjects = repo.getSubjectsFlow()

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    // FIX: was MutableLiveData — replays on rotation. Now SingleLiveEvent fires once only.
    private val _error = SingleLiveEvent<String>()
    val error: LiveData<String> = _error

    val notificationsEnabled = repo.prefs.notificationsEnabled

    init { refresh() }

    fun refresh() {
        if (_isRefreshing.value == true) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repo.initialSync()
            } catch (e: Exception) {
                _error.call("Failed to refresh. Check your connection.")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleNotifications(enabled: Boolean) {
        viewModelScope.launch { repo.prefs.setNotificationsEnabled(enabled) }
    }

    fun logout() {
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

    private lateinit var binding: ActivitySubjectsBinding
    private val viewModel: SubjectsViewModel by viewModels { SubjectsViewModelFactory(applicationContext) }
    private lateinit var adapter: SubjectAdapter

    // FIX: menu notification state is tracked here, not in a leaking coroutine
    // launched inside onPrepareOptionsMenu.
    private var notificationsOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "My Attendance"

        setupRecyclerView()
        setupSwipeRefresh()
        observeData()

        // FIX: collect notification state ONCE in onCreate, not on every
        // onPrepareOptionsMenu call (which launched a new coroutine each time,
        // leaking collectors and causing menu title flicker).
        lifecycleScope.launch {
            viewModel.notificationsEnabled.collect { enabled ->
                notificationsOn = enabled
                invalidateOptionsMenu()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_subjects, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // FIX: reads from local state updated by the single collector above —
        // no coroutine launched here, no leak.
        menu.findItem(R.id.action_notifications)?.title =
            if (notificationsOn) "Notifications: ON" else "Notifications: OFF"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_notifications -> {
                val newEnabled = !notificationsOn
                viewModel.toggleNotifications(newEnabled)
                if (newEnabled) AttendanceSyncWorker.schedule(applicationContext)
                else AttendanceSyncWorker.cancel(applicationContext)
                Snackbar.make(
                    binding.root,
                    if (newEnabled) "Notifications enabled" else "Notifications disabled",
                    Snackbar.LENGTH_SHORT
                ).show()
                true
            }
            R.id.action_update -> {
                startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/your-repo/mit-attendance/releases"))
                )
                true
            }
            R.id.action_logout -> {
                viewModel.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = SubjectAdapter { subject ->
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
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.subjects.collectLatest { subjects ->
                adapter.submitList(subjects)
                binding.tvEmpty.visibility = if (subjects.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewModel.isRefreshing.observe(this) { binding.swipeRefresh.isRefreshing = it }
        // FIX: SingleLiveEvent — will only fire once, never replays on rotation
        viewModel.error.observe(this) { msg ->
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class SubjectAdapter(
    private val onClick: (SubjectUiModel) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.VH>() {

    // FIX: was a plain mutable list updated manually — unsafe under rapid submitList calls.
    // AsyncListDiffer runs diff on a background thread and applies updates safely.
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<SubjectUiModel>() {
        override fun areItemsTheSame(o: SubjectUiModel, n: SubjectUiModel) = o.id == n.id
        override fun areContentsTheSame(o: SubjectUiModel, n: SubjectUiModel) = o == n
    })

    fun submitList(newList: List<SubjectUiModel>) = differ.submitList(newList)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(differ.currentList[position])
    override fun getItemCount() = differ.currentList.size

    inner class VH(private val binding: ItemSubjectBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(s: SubjectUiModel) {
            binding.tvSubjectName.text = s.name
            binding.tvStats.text = "${s.present}/${s.total} classes attended"
            binding.tvPercentage.text = "${s.percentage.toInt()}%"

            val ctx = binding.root.context
            val color = when {
                s.percentage >= 75 -> ContextCompat.getColor(ctx, R.color.green)
                s.percentage >= 60 -> ContextCompat.getColor(ctx, R.color.yellow)
                else               -> ContextCompat.getColor(ctx, R.color.red)
            }
            binding.tvPercentage.setTextColor(color)
            binding.progressBar.progress = s.percentage.toInt()
            binding.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            binding.badgeNew.visibility = if (s.hasNewEntries) View.VISIBLE else View.GONE

            val neededText = neededLabel(s)
            binding.tvNeeded.text = neededText
            binding.tvNeeded.visibility = if (neededText.isNotEmpty()) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onClick(s) }
        }

        private fun neededLabel(s: SubjectUiModel): String {
            if (s.total == 0) return ""
            return if (s.percentage < 75f) {
                val needed = Math.ceil((0.75 * s.total - s.present) / 0.25).toInt()
                if (needed > 0) "Attend $needed more to reach 75%" else ""
            } else {
                val canSkip = Math.floor((s.present - 0.75 * s.total) / 0.75).toInt()
                if (canSkip > 0) "Can skip $canSkip class(es)" else ""
            }
        }
    }
}