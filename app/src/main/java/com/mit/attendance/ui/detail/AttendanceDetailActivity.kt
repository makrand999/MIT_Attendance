package com.mit.attendance.ui.detail

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
import com.mit.attendance.databinding.ActivityAttendanceDetailBinding
import com.mit.attendance.databinding.ItemAttendanceBinding
import com.mit.attendance.model.AttendanceUiModel
import com.mit.attendance.model.SingleLiveEvent
import com.mit.attendance.model.SyncResult
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class DetailViewModel(
    private val repo: AttendanceRepository,
    private val subjectId: String
) : ViewModel() {

    val attendance = repo.getAttendanceFlow(subjectId)

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // FIX: was MutableLiveData — snackbar replayed on every rotation.
    // SingleLiveEvent fires once and is consumed.
    private val _syncMessage = SingleLiveEvent<String>()
    val syncMessage: LiveData<String> = _syncMessage

    init { fetch() }

    fun fetch() {
        if (_isLoading.value == true) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repo.syncAttendanceDetail(subjectId)
                _syncMessage.call(when (result) {
                    is SyncResult.Success      -> "✅ ${result.newCount} new record(s) fetched"
                    is SyncResult.NoChange     -> "✓ Already up to date"
                    is SyncResult.NetworkError -> "⚠️ Network error — showing cached data"
                    is SyncResult.SessionError -> "⚠️ Session expired — please log in again"
                    is SyncResult.ServerOffline -> "🔴 Server is offline (521) — showing cached data"
                })
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

        // FIX: guard against missing extra — finish early instead of silently
        // running all DB queries with subjectId = "" and showing an empty screen.
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
        binding.swipeRefresh.setOnRefreshListener { viewModel.fetch() }
       // binding.fabRefresh.setOnClickListener { viewModel.fetch() }
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

                val total = records.size
                val present = records.count { it.status == "P" }
                val pct = if (total > 0) (present * 100f / total) else 0f
                binding.tvSummary.text =
                    "Present: $present | Absent: ${total - present} | ${pct.toInt()}%"
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            // progress_bar only shows on very first load when list is empty
            binding.progressBar.visibility =
                if (loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            // swipe refresh spinner stops when done
            binding.swipeRefresh.isRefreshing = loading
        }

        // FIX: SingleLiveEvent — no null check needed, fires once only
        viewModel.syncMessage.observe(this) { msg ->
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class AttendanceAdapter : RecyclerView.Adapter<AttendanceAdapter.VH>() {

    // FIX: AsyncListDiffer replaces the manual mutable list + calculateDiff pattern.
    // Diff runs on a background thread; no race condition between submitList calls.
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

            if (record.isNew) {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.new_entry_bg))
                binding.badgeNew.visibility = View.VISIBLE
            } else {
                binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_background))
                binding.badgeNew.visibility = View.GONE
            }
        }
    }
}