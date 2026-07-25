package com.mit.attendance.ui.subjects

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.databinding.ActivityAttendanceSubjectsBinding
import com.mit.attendance.model.SubjectUiModel
import com.mit.attendance.ui.detail.AttendanceDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AttendanceSubjectsActivity : AppCompatActivity() {

    private val tag = "AttendanceSubjects"

    private lateinit var binding: ActivityAttendanceSubjectsBinding
    private val viewModel: SubjectsViewModel by viewModels { SubjectsViewModelFactory(applicationContext) }
    private lateinit var adapter: SubjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceSubjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.medkit_cream)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.medkit_cream)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Overall Attendance"
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.medkit_line))

        setupRecycler()
        observeData()

        binding.swipeRefresh.setColorSchemeResources(
            R.color.medkit_peach_deep,
            R.color.medkit_mint_deep,
            R.color.medkit_sky_deep
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.medkit_paper)
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    private fun setupRecycler() {
        adapter = SubjectAdapter(::openAttendanceDetail)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.subjects.collectLatest { subjects ->
                    Log.d(tag, "Rendering ${subjects.size} subjects")
                    binding.tvEmpty.visibility = if (subjects.isEmpty()) View.VISIBLE else View.GONE
                    adapter.submitList(subjects) {
                        binding.recyclerView.post {
                            binding.recyclerView.requestLayout()
                            binding.recyclerView.invalidateItemDecorations()
                        }
                    }
                }
            }
        }

        viewModel.isRefreshing.observe(this) { refreshing ->
            binding.swipeRefresh.isRefreshing = refreshing
        }

        viewModel.error.observe(this) { msg ->
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun openAttendanceDetail(subject: SubjectUiModel) {
        startActivity(
            Intent(this, AttendanceDetailActivity::class.java).apply {
                putExtra(AttendanceDetailActivity.EXTRA_SUBJECT_ID, subject.id)
                putExtra(AttendanceDetailActivity.EXTRA_SUBJECT_NAME, subject.name)
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
