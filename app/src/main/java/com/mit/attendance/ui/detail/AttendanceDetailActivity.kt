package com.mit.attendance.ui.detail

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.*
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivityAttendanceDetailBinding
import com.mit.attendance.databinding.ItemAttendanceBinding
import com.mit.attendance.databinding.ItemAttendanceHeaderBinding
import com.mit.attendance.model.AttendanceUiModel
import com.mit.attendance.model.SingleLiveEvent
import com.mit.attendance.ui.alerts.AlertsActivity
import com.mit.attendance.ui.theme.MedKitMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

// ── ViewModel ─────────────────────────────────────────────────────────────────

class DetailViewModel(
    private val repo: AttendanceRepository,
    private val subjectId: String
) : ViewModel() {

    val attendance = repo.getAttendanceFlow(subjectId)
    val bgDetailUri = repo.prefs.bgDetailUri

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _syncMessage = SingleLiveEvent<String>()
    val syncMessage: LiveData<String> = _syncMessage

    init { fetch() }

    fun fetch() {
        if (_isLoading.value == true) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repo.syncAttendanceDetail(subjectId)
            } catch (e: Exception) {
                _syncMessage.call("⚠️ Unexpected error: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun markAsSeen() {
        viewModelScope.launch { repo.markAttendanceAsSeen(subjectId) }
    }
}

class DetailViewModelFactory(
    private val context: android.content.Context,
    private val subjectId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return DetailViewModel(AttendanceRepository(context), subjectId) as T
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class AttendanceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_SUBJECT_NAME = "subject_name"
    }

    private lateinit var binding: ActivityAttendanceDetailBinding
    private lateinit var viewModel: DetailViewModel
    private lateinit var headerAdapter: AttendanceHeaderAdapter
    private lateinit var adapter: AttendanceAdapter
    private lateinit var gestureDetector: GestureDetector
    private var subjectName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID)
        if (subjectId.isNullOrBlank()) {
            finish()
            return
        }
        subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: "Attendance"

        val vm: DetailViewModel by viewModels { DetailViewModelFactory(applicationContext, subjectId) }
        viewModel = vm

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = subjectName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.medkit_line))

        setupRecyclerView()
        setupGestureDetector()
        observeData()

        binding.swipeRefresh.setColorSchemeResources(
            R.color.medkit_peach_deep,
            R.color.medkit_mint_deep,
            R.color.medkit_sky_deep
        )
        binding.swipeRefresh.isEnabled = true
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetch()
        }
        MedKitMotion.playEntrance(binding.toolbar)
        MedKitMotion.applyRecyclerStagger(binding.recyclerView)

        lifecycleScope.launch {
            viewModel.bgDetailUri.collectLatest { uri ->
                if (uri != null) {
                    binding.ivBackground.setImageURI(uri.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_medkit_screen)
                }
            }
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Swipe Left (Right to Left)
                if (abs(diffX) > abs(diffY) && diffX < -150 && abs(velocityX) > 150) {
                    openAlertsForSubject()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openAlertsForSubject() {
        val intent = Intent(this, AlertsActivity::class.java).apply {
            putExtra(AlertsActivity.EXTRA_FILTER_SUBJECT, subjectName)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun onStop() {
        super.onStop()
        viewModel.markAsSeen()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupRecyclerView() {
        headerAdapter = AttendanceHeaderAdapter(::openAlertsForSubject)
        adapter = AttendanceAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ConcatAdapter(headerAdapter, adapter)
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.attendance.collectLatest { records ->
                adapter.submitList(records)
                headerAdapter.submitRecords(records)
                MedKitMotion.applyRecyclerStagger(binding.recyclerView)
                binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.toolbarProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
        }

        viewModel.syncMessage.observe(this) { msg ->
            if (msg.startsWith("⚠️")) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class AttendanceAdapter : RecyclerView.Adapter<AttendanceAdapter.VH>() {

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<AttendanceUiModel>() {
        override fun areItemsTheSame(o: AttendanceUiModel, n: AttendanceUiModel) =
            o.date == n.date && o.startTime == n.startTime
        override fun areContentsTheSame(o: AttendanceUiModel, n: AttendanceUiModel) = o == n
    })

    fun submitList(newList: List<AttendanceUiModel>) = differ.submitList(newList)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAttendanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(differ.currentList[position], position)
    override fun getItemCount() = differ.currentList.size

    inner class VH(private val binding: ItemAttendanceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: AttendanceUiModel, position: Int) {
            val ctx = binding.root.context
            val isPresent = record.status == "P"
            binding.root.rotation = stuckCardRotation(position)
            binding.root.translationX = stuckCardOffsetX(position)
            binding.root.translationY = stuckCardOffsetY(position)

            binding.tvDate.text = record.date
            binding.tvTime.text = "${record.startTime} – ${record.endTime}"
            binding.tvStatus.text = if (isPresent) "Present" else "Absent"

            val statusColor = if (isPresent)
                ContextCompat.getColor(ctx, R.color.medkit_mint_deep)
            else
                ContextCompat.getColor(ctx, R.color.medkit_rose_deep)

            binding.tvStatus.setBackgroundResource(
                if (isPresent) R.drawable.bg_attendance_present else R.drawable.bg_attendance_absent
            )
            binding.statusIndicator.backgroundTintList = ColorStateList.valueOf(statusColor)

            binding.root.strokeColor = if (record.isNew) {
                ContextCompat.getColor(ctx, R.color.medkit_butter_deep)
            } else {
                ContextCompat.getColor(ctx, R.color.medkit_line)
            }
            binding.badgeNew.visibility = if (record.isNew) View.VISIBLE else View.GONE
        }
    }
}

private fun stuckCardRotation(position: Int): Float = when (position % 6) {
    0 -> -1.2f
    1 -> 0.9f
    2 -> 0.55f
    3 -> -0.95f
    4 -> -0.45f
    else -> 1.15f
}

private fun stuckCardOffsetX(position: Int): Float = when (position % 4) {
    0 -> -3f
    1 -> 4f
    2 -> 2f
    else -> -4f
}

private fun stuckCardOffsetY(position: Int): Float = when (position % 4) {
    0 -> 0f
    1 -> 8f
    2 -> -5f
    else -> 6f
}

private class AttendanceHeaderAdapter(
    private val onOpenAlerts: () -> Unit
) : RecyclerView.Adapter<AttendanceHeaderAdapter.HeaderViewHolder>() {

    private var records: List<AttendanceUiModel> = emptyList()
    private var overviewExpanded = false

    fun submitRecords(newRecords: List<AttendanceUiModel>) {
        records = newRecords
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = ItemAttendanceHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(
            binding = binding,
            onOpenAlerts = onOpenAlerts,
            onToggleOverview = {
                overviewExpanded = !overviewExpanded
                notifyItemChanged(0)
            }
        )
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(records, overviewExpanded)
    }

    override fun getItemCount(): Int = 1

    class HeaderViewHolder(
        private val binding: ItemAttendanceHeaderBinding,
        onOpenAlerts: () -> Unit,
        onToggleOverview: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnOpenAlerts.setOnClickListener { onOpenAlerts() }
            binding.btnOverviewToggle.setOnClickListener { onToggleOverview() }
        }

        fun bind(records: List<AttendanceUiModel>, expanded: Boolean) {
            val context = binding.root.context
            binding.overviewDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnOverviewToggle.text = if (expanded) "Hide overview" else "Overview"
            binding.btnOverviewToggle.rotation = if (expanded) 0.35f else -0.35f
            binding.cardSummary.setCardBackgroundColor(ContextCompat.getColor(context, R.color.white))
            binding.cardSummary.strokeColor = ContextCompat.getColor(context, R.color.medkit_line)

            if (records.isEmpty()) {
                binding.tvPercentageSummary.text = "0%"
                binding.tvPercentageSummary.setTextColor(ContextCompat.getColor(context, R.color.medkit_ink))
                binding.tvStatusCaption.text = "Pull to refresh the latest attendance timeline."
                binding.tvStatsSummary.text = "0 present / 0 total"
                binding.tvNeededLabel.text = "Need for 75%"
                binding.tvNeededSummary.text = "0"
                binding.tvNeededSummary.setTextColor(ContextCompat.getColor(context, R.color.medkit_ink))
                return
            }

            val total = records.size
            val present = records.count { it.status == "P" }
            val percentage = (present * 100f / total)
            val summaryColor = when {
                percentage >= 75f -> ContextCompat.getColor(context, R.color.medkit_mint_deep)
                percentage >= 60f -> ContextCompat.getColor(context, R.color.medkit_butter_deep)
                else -> ContextCompat.getColor(context, R.color.medkit_rose_deep)
            }

            binding.tvPercentageSummary.text = "${percentage.toInt()}%"
            binding.tvPercentageSummary.setTextColor(summaryColor)
            binding.tvStatsSummary.text = "$present present / $total total"

            if (percentage < 75f) {
                val needed = Math.ceil((0.75 * total - present) / 0.25).toInt()
                binding.tvNeededLabel.text = "Need for 75%"
                binding.tvNeededSummary.text = if (needed > 0) "$needed classes" else "0"
                binding.tvNeededSummary.setTextColor(ContextCompat.getColor(context, R.color.medkit_rose_deep))
                binding.tvStatusCaption.text = "You are below the safe zone. Attend the next few classes to recover."
            } else {
                val canSkip = Math.floor((present - 0.75 * total) / 0.75).toInt()
                binding.tvNeededLabel.text = "Can miss"
                binding.tvNeededSummary.text = if (canSkip > 0) "$canSkip classes" else "0"
                binding.tvNeededSummary.setTextColor(ContextCompat.getColor(context, R.color.medkit_mint_deep))
                binding.tvStatusCaption.text = "You are on track. Keep an eye on upcoming lectures and alerts."
            }
        }
    }
}
