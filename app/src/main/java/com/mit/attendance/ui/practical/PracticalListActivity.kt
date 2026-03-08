package com.mit.attendance.ui.practical

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityPracticalListBinding
import com.mit.attendance.databinding.ItemPracticalBinding
import com.mit.attendance.model.Practical

class PracticalListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPracticalListBinding
    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(UserPreferences(applicationContext))
    }
    private lateinit var adapter: PracticalAdapter
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

        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()

        viewModel.init()
    }

    private fun setupRecyclerView() {
        adapter = PracticalAdapter { practical ->
            val isSubmitted = practical.theory != null
            val intent = Intent(this, SubmitPracticalActivity::class.java).apply {
                putExtra(PracticalActivity.EXTRA_SUBJECT_ID, subjectId)
                putExtra("practical_id", practical.id)
                putExtra("practical_number", practical.practicalNumber)
                putExtra("practical_title", practical.practicalAim)
                putExtra("practical_desc", practical.practicalDescription)
                putExtra("is_submitted", isSubmitted)
                putExtra("existing_theory", practical.theory)
                putExtra("existing_concl", practical.conclusion)
            }
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
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

        viewModel.practicals.observe(this) { state ->
            when (state) {
                is UiState.Loading -> binding.swipeRefresh.isRefreshing = true
                is UiState.Success -> {
                    binding.swipeRefresh.isRefreshing = false
                    val all = state.data.notSubmitted + state.data.submitted
                    adapter.submitList(all.sortedBy { it.practicalNumber })
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class PracticalAdapter(private val onClick: (Practical) -> Unit) :
    RecyclerView.Adapter<PracticalAdapter.ViewHolder>() {

    private var items = listOf<Practical>()

    fun submitList(newItems: List<Practical>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPracticalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemPracticalBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(practical: Practical) {
            binding.tvPracticalNumber.text = "Practical ${practical.practicalNumber}"
            binding.tvPracticalAim.text = practical.practicalAim
            
            val isSubmitted = practical.theory != null
            binding.tvStatus.text = if (isSubmitted) "Submitted" else "Pending"
            binding.tvStatus.setTextColor(if (isSubmitted) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))

            binding.root.setOnClickListener { onClick(practical) }
        }
    }
}
