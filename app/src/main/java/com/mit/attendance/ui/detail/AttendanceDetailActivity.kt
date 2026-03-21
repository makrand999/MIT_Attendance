package com.mit.attendance.ui.detail

import android.os.Bundle
import android.view.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.*
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.databinding.ActivityAttendanceDetailBinding
import com.mit.attendance.databinding.ItemAttendanceBinding
import com.mit.attendance.model.AttendanceUiModel
import com.mit.attendance.model.SingleLiveEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                // Removed _syncMessage.call to stop "Up to date" etc popups
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
    private lateinit var adapter: AttendanceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID)
        if (subjectId.isNullOrBlank()) {
            finish()
            return
        }
        val subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: "Attendance"

        val vm: DetailViewModel by viewModels { DetailViewModelFactory(applicationContext, subjectId) }
        viewModel = vm

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = subjectName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupRecyclerView()
        observeData()

        binding.swipeRefresh.setColorSchemeResources(R.color.primary, R.color.accent)
        binding.swipeRefresh.isEnabled = true // Keep pull-to-refresh enabled
        binding.swipeRefresh.setOnRefreshListener { 
            viewModel.fetch()
            binding.swipeRefresh.isRefreshing = false // Stop pull animation immediately
        }

        lifecycleScope.launch {
            viewModel.bgDetailUri.collectLatest { uri ->
                if (uri != null) {
                    binding.ivBackground.setImageURI(uri.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_detail)
                }
            }
        }
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
        adapter = AttendanceAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.attendance.collectLatest { records ->
                adapter.submitList(records)
                binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

                if (records.isNotEmpty()) {
                    val total = records.size
                    val present = records.count { it.status == "P" }
                    val percentage = (present * 100f / total)
                    
                    binding.tvStatsSummary.text = "$present/$total"
                    
                    if (percentage < 75f) {
                        val needed = Math.ceil((0.75 * total - present) / 0.25).toInt()
                        binding.tvNeededSummary.text = if (needed > 0) "-$needed" else "0"
                        binding.tvNeededSummary.setTextColor(ContextCompat.getColor(this@AttendanceDetailActivity, R.color.red_light))
                    } else {
                        val canSkip = Math.floor((present - 0.75 * total) / 0.75).toInt()
                        binding.tvNeededSummary.text = if (canSkip > 0) "+$canSkip" else "0"
                        binding.tvNeededSummary.setTextColor(ContextCompat.getColor(this@AttendanceDetailActivity, R.color.green_light))
                    }
                } else {
                    binding.tvStatsSummary.text = "0/0"
                    binding.tvNeededSummary.text = "0"
                }
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            // Move loading indicator to the toolbar ProgressBar
            binding.toolbarProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
            // Ensure central progress bar and swipe refresh animation stay hidden
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
        }

        viewModel.syncMessage.observe(this) { msg ->
            // Only show snackbar for actual errors
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

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(differ.currentList[position])
    override fun getItemCount() = differ.currentList.size

    inner class VH(private val binding: ItemAttendanceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: AttendanceUiModel) {
            val ctx = binding.root.context
            val isPresent = record.status == "P"

            binding.tvDate.text = record.date
            binding.tvTime.text = "${record.startTime} – ${record.endTime}"
            binding.tvStatus.text = if (isPresent) "Present" else "Absent"

            val statusColor = if (isPresent)
                ContextCompat.getColor(ctx, R.color.green)
            else
                ContextCompat.getColor(ctx, R.color.red)

            binding.tvStatus.setTextColor(statusColor)
            binding.statusIndicator.setBackgroundColor(statusColor)

            // Force transparency
            binding.root.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.badgeNew.visibility = if (record.isNew) View.VISIBLE else View.GONE
        }
    }
}