package com.mit.attendance.ui.practical

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.PracticalSubject

class PracticalActivity : AppCompatActivity() {

    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(application, UserPreferences(applicationContext))
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: View
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practical)

        supportActionBar?.apply {
            title = "Practicals"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.rvSubjects)
        progressBar  = findViewById(R.id.progressBar)
        emptyText    = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)

        viewModel.loginState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> showLoading(true)
                is UiState.Error   -> {
                    showLoading(false)
                    Toast.makeText(this, "Login failed: ${state.message}", Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }

        viewModel.subjects.observe(this) { state ->
            when (state) {
                is UiState.Loading -> showLoading(true)
                is UiState.Success -> {
                    showLoading(false)
                    if (state.data.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyText.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.adapter = SubjectAdapter(state.data) { subject ->
                            val intent = Intent(this, PracticalListActivity::class.java).apply {
                                putExtra(EXTRA_SUBJECT_ID,   subject.id)
                                putExtra(EXTRA_SUBJECT_NAME, subject.subjectname)
                            }
                            startActivity(intent)
                        }
                    }
                }
                is UiState.Error -> {
                    showLoading(false)
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }

        viewModel.init()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 999, 0, "Test AI Implementation")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 999) {
            val intent = Intent(this, SubmitPracticalActivity::class.java).apply {
                putExtra(EXTRA_SUBJECT_ID, 0)
                putExtra("practical_id", 0)
                putExtra("practical_number", 1)
                putExtra("practical_title", "Test Practical")
                putExtra("practical_desc", "This is a test description to see if ChatGPT generates theory correctly.")
                putExtra("is_submitted", false)
            }
            startActivity(intent)
            return true
        }
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_SUBJECT_ID   = "subject_id"
        const val EXTRA_SUBJECT_NAME = "subject_name"
    }

    // ── Adapter ────────────────────────────────────────────────────────────────

    private class SubjectAdapter(
        private val items: List<PracticalSubject>,
        private val onClick: (PracticalSubject) -> Unit
    ) : RecyclerView.Adapter<SubjectAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvSubjectName)
            val code: TextView = view.findViewById(R.id.tvSubjectCode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_practical_subject, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.subjectname
            holder.code.text = item.subjectcode
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }
}
