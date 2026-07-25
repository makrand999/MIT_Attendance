package com.mit.attendance.ui.practical

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityPracticalListBinding
import com.mit.attendance.databinding.ItemPracticalBinding
import com.mit.attendance.databinding.ItemPracticalListHeaderBinding
import com.mit.attendance.model.Practical
import com.mit.attendance.model.PracticalServerStatus
import com.mit.attendance.ui.theme.MedKitMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PracticalListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPracticalListBinding
    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(application, UserPreferences(applicationContext))
    }
    private lateinit var adapter: PracticalAdapter
    private lateinit var headerAdapter: PracticalListHeaderAdapter
    private var subjectId: Int = -1
    private var subjectName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPracticalListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subjectId = intent.getIntExtra(PracticalActivity.EXTRA_SUBJECT_ID, -1)
        subjectName = intent.getStringExtra(PracticalActivity.EXTRA_SUBJECT_NAME) ?: "Practicals"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = subjectName
        }
        binding.toolbar.navigationIcon?.setTint(getColor(R.color.medkit_line))

        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
        renderHeader(emptyList())
        MedKitMotion.playEntrance(binding.toolbar)
        MedKitMotion.applyRecyclerStagger(binding.recyclerView)

        viewModel.init(loadSubjects = false)
    }

    private fun setupRecyclerView() {
        headerAdapter = PracticalListHeaderAdapter().apply {
            updateTitle(subjectName)
            updateSubtitle("Open any practical to review details or submit your work.")
        }
        adapter = PracticalAdapter { practical ->
            val isSubmitted = practical.isSubmitted
            val intent = Intent(this, SubmitPracticalActivity::class.java).apply {
                putExtra(PracticalActivity.EXTRA_SUBJECT_ID,   subjectId)
                putExtra(PracticalActivity.EXTRA_SUBJECT_NAME, subjectName)
                putExtra("practical_id", practical.id)
                putExtra("practical_number", practical.practicalNumber)
                putExtra("practical_title", practical.practicalAim)
                putExtra("practical_desc", practical.practicalDescription)
                putExtra("expected_output", practical.expectedOutput)
                putExtra("is_submitted", isSubmitted)
                putExtra("existing_theory", practical.theory)
                putExtra("existing_code", practical.code)
                putExtra("existing_output", practical.output)
                putExtra("existing_error", practical.error)
                putExtra("existing_concl", practical.conclusion)
                putExtra("existing_language", practical.language)
                putExtra("existing_submission_id", practical.submissionId ?: 0)
                putExtra("existing_pdf_name", practical.practicalDoc)
                putExtra("local_draft_saved_at", practical.localDraftSavedAt ?: 0L)
                putExtra("last_server_sync_at", practical.lastServerSyncAt ?: 0L)
                putExtra("last_server_verified_at", practical.lastServerVerifiedAt ?: 0L)
                putExtra("pending_sync_action", practical.pendingSyncAction)
                putExtra("last_sync_error", practical.lastSyncError)
            }
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.adapter = ConcatAdapter(headerAdapter, adapter)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.medkit_peach_deep,
            R.color.medkit_mint_deep,
            R.color.medkit_sky_deep
        )
        binding.swipeRefresh.setOnRefreshListener {
            if (subjectId != -1) viewModel.loadPracticals(subjectId)
            else binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeViewModel() {
        viewModel.loginState.observe(this) { state ->
            if (state is UiState.Success && subjectId != -1) {
                viewModel.loadPracticals(subjectId)
            } else if (state is UiState.Error) {
                Snackbar.make(binding.root, "Login failed: ${state.message}", Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.serverStatus.observe(this) { status ->
            val subtitle = when (status) {
                PracticalServerStatus.AVAILABLE ->
                    "Server available. Submission status is being verified from the server."
                PracticalServerStatus.WIFI_REQUIRED ->
                    "Offline list loaded. Connect to college Wi-Fi to verify submission status."
                PracticalServerStatus.SERVER_DOWN ->
                    "Offline list loaded. Server appears down, so submission status is not verified yet."
                PracticalServerStatus.UNKNOWN ->
                    null
            }
            subtitle?.let { headerAdapter.updateSubtitle(it) }
        }

        viewModel.practicals.observe(this) { state ->
            when (state) {
                is UiState.Loading -> binding.swipeRefresh.isRefreshing = true
                is UiState.Success -> {
                    binding.swipeRefresh.isRefreshing = false
                    val all = state.data.notSubmitted + state.data.submitted
                    adapter.submitList(all.sortedBy { it.practicalNumber })
                    MedKitMotion.applyRecyclerStagger(binding.recyclerView)
                    renderHeader(all)
                    binding.tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
                }
                is UiState.Error -> {
                    binding.swipeRefresh.isRefreshing = false
                    Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                }
                else -> {}
            }
        }
    }

    private fun renderHeader(practicals: List<Practical>) {
        val pending = practicals.count { !it.isSubmitted }
        val submitted = practicals.size - pending
        headerAdapter.updateCounts(
            total = practicals.size.toString(),
            pending = pending.toString(),
            submitted = submitted.toString()
        )
        headerAdapter.updateSubtitle(
            if (practicals.isEmpty()) {
                "Pull to refresh and load the latest practical list."
            } else {
                "$pending pending and $submitted submitted practicals in $subjectName."
            }
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

private class PracticalListHeaderAdapter : RecyclerView.Adapter<PracticalListHeaderAdapter.HeaderViewHolder>() {
    private var title: String = "Practicals"
    private var subtitle: String = "Open any practical to review details or submit your work."
    private var total: String = "0"
    private var pending: String = "0"
    private var submitted: String = "0"

    fun updateTitle(value: String) {
        title = value
        notifyItemChanged(0)
    }

    fun updateSubtitle(value: String) {
        subtitle = value
        notifyItemChanged(0)
    }

    fun updateCounts(total: String, pending: String, submitted: String) {
        this.total = total
        this.pending = pending
        this.submitted = submitted
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = ItemPracticalListHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.binding.root.rotation = -1.0f
        holder.binding.tvHeaderTitle.text = title
        holder.binding.tvHeaderSubtitle.text = subtitle
        holder.binding.tvPracticalTotal.text = total
        holder.binding.tvPracticalPending.text = pending
        holder.binding.tvPracticalSubmitted.text = submitted
    }

    override fun getItemCount(): Int = 1

    class HeaderViewHolder(val binding: ItemPracticalListHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}

class PracticalAdapter(private val onClick: (Practical) -> Unit) :
    ListAdapter<Practical, PracticalAdapter.ViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPracticalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), position)

    inner class ViewHolder(private val binding: ItemPracticalBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(practical: Practical, position: Int) {
            binding.root.rotation = stuckCardRotation(position)
            binding.root.translationX = stuckCardOffsetX(position)
            binding.root.translationY = stuckCardOffsetY(position)
            binding.tvPracticalNumber.text = "Practical ${practical.practicalNumber}"
            binding.tvPracticalAim.text = practical.practicalAim
            binding.tvPracticalDesc.text = practical.practicalDescription
            binding.tvPracticalDesc.visibility =
                if (practical.practicalDescription.isNullOrBlank()) View.GONE else View.VISIBLE

            val status = practical.buildStatus()
            binding.tvStatus.text = status.label
            binding.tvStatus.setBackgroundResource(status.backgroundRes)
            binding.tvStatusMeta.text = status.meta
            binding.tvActionHint.text = status.hint

            binding.root.setOnClickListener { onClick(practical) }
        }
    }

    private data class PracticalStatusUi(
        val label: String,
        val meta: String,
        val hint: String,
        val backgroundRes: Int
    )

    private fun Practical.buildStatus(): PracticalStatusUi {
        val localSaved = localDraftSavedAt
        val serverSync = lastServerSyncAt
        val serverVerified = lastServerVerifiedAt
        val syncError = lastSyncError?.takeIf { it.isNotBlank() }
        val hasUnsyncedLocalDraft = localSaved != null && (serverSync == null || localSaved > serverSync)

        return when {
            isSubmitted -> PracticalStatusUi(
                label = "Submitted",
                meta = "Verified ${formatTimestamp(serverVerified ?: serverSync)}",
                hint = "Tap to review or update your verified submission",
                backgroundRes = com.mit.attendance.R.drawable.bg_status_submitted
            )

            syncError != null -> PracticalStatusUi(
                label = "Needs Sync",
                meta = syncError,
                hint = "Tap to retry the last server action or keep working offline",
                backgroundRes = com.mit.attendance.R.drawable.bg_status_needs_sync
            )

            hasUnsyncedLocalDraft -> PracticalStatusUi(
                label = "Draft Saved",
                meta = "Saved locally ${formatTimestamp(localSaved)}",
                hint = "Tap to continue offline or sync this draft to the server",
                backgroundRes = com.mit.attendance.R.drawable.bg_status_draft_saved
            )

            serverVerified != null -> PracticalStatusUi(
                label = "Server Verified",
                meta = "Verified ${formatTimestamp(serverVerified)}",
                hint = "Tap to review the verified server copy",
                backgroundRes = com.mit.attendance.R.drawable.bg_status_server_verified
            )

            else -> PracticalStatusUi(
                label = "Offline Only",
                meta = "Open on college Wi-Fi to verify server status",
                hint = "Tap to work from offline cache and local draft storage",
                backgroundRes = com.mit.attendance.R.drawable.bg_status_offline_only
            )
        }
    }

    private fun formatTimestamp(value: Long?): String {
        if (value == null || value <= 0L) return "not yet"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(value))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Practical>() {
            override fun areItemsTheSame(oldItem: Practical, newItem: Practical): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Practical, newItem: Practical): Boolean =
                oldItem == newItem
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
