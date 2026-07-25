package com.mit.attendance.ui.practical

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.PracticalServerStatus
import com.mit.attendance.model.PracticalSubject
import com.mit.attendance.ui.theme.MedKitMotion

class PracticalActivity : AppCompatActivity() {

    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(application, UserPreferences(applicationContext))
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: View
    private lateinit var emptyText: TextView
    private lateinit var infoText: TextView
    private lateinit var adapter: SubjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practical)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            title = "Practicals"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.rvSubjects)
        progressBar  = findViewById(R.id.progressBar)
        emptyText    = findViewById(R.id.tvEmpty)
        infoText     = findViewById(R.id.tvPracticalInfo)

        adapter = SubjectAdapter { subject ->
            val intent = Intent(this, PracticalListActivity::class.java).apply {
                putExtra(EXTRA_SUBJECT_ID, subject.id)
                putExtra(EXTRA_SUBJECT_NAME, subject.subjectname)
            }
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(true)
        recyclerView.adapter = adapter
        findViewById<Toolbar>(R.id.toolbar).navigationIcon?.setTint(getColor(R.color.medkit_line))
        MedKitMotion.playEntrance(findViewById(R.id.toolbar), infoText)
        MedKitMotion.applyRecyclerStagger(recyclerView)

        viewModel.loginState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> showLoading(true)
                is UiState.Error   -> {
                    showLoading(false)
                    handlePracticalLoginError(state.message)
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
                        adapter.submitList(state.data)
                        MedKitMotion.applyRecyclerStagger(recyclerView)
                    }
                }
                is UiState.Error -> {
                    showLoading(false)
                    handlePracticalLoginError(state.message)
                }
                else -> {}
            }
        }

        viewModel.serverStatus.observe(this) { status ->
            infoText.text = when (status) {
                PracticalServerStatus.AVAILABLE ->
                    "Practical server is available. Offline cache is still preferred first: load your practicals on college Wi-Fi, work offline or on cellular, then reconnect to sync drafts or submit. ChatGPT AI may still be slow or blocked on college Wi-Fi."
                PracticalServerStatus.WIFI_REQUIRED ->
                    "Practical server is not reachable right now. Connect to college Wi-Fi to refresh or submit. Cached subjects, practical lists, and local drafts remain available offline."
                PracticalServerStatus.SERVER_DOWN ->
                    "The practical server looks down right now. You can keep working from cached practical data and local drafts, then retry submission later on college Wi-Fi."
                PracticalServerStatus.UNKNOWN ->
                    "Best workflow: first open Practicals on college Wi-Fi and let the app load subjects and practicals for offline use. Then you can write theory, code, output, and drafts on cellular or offline. Go back to college Wi-Fi when you want to sync drafts or submit. If default login fails, you can enter your own practical password. AI helpers may be slow or blocked on college Wi-Fi due to network restrictions."
            }
        }

        viewModel.init()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1000, 0, "Help")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu?.add(0, 999, 0, "Test AI Implementation")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1000) {
            showPracticalHelpDialog()
            return true
        }
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

    private fun handlePracticalLoginError(message: String) {
        when (normalizePracticalErrorKey(message)) {
            "PRACTICAL_PASSWORD_REQUIRED" -> showPracticalPasswordPrompt()
            "PRACTICAL_WIFI_REQUIRED" -> showWifiInstructionDialog()
            "PRACTICAL_SERVER_DOWN" -> showServerDownDialog()
            else -> Toast.makeText(this, "Login failed: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun normalizePracticalErrorKey(message: String): String {
        val lower = message.lowercase()
        return when {
            message == "PRACTICAL_PASSWORD_REQUIRED" -> message
            message == "PRACTICAL_WIFI_REQUIRED" -> message
            message == "PRACTICAL_SERVER_DOWN" -> message
            lower.contains("user not found") ||
                lower.contains("invalid") ||
                lower.contains("credential") ||
                lower.contains("password") -> "PRACTICAL_PASSWORD_REQUIRED"
            lower.contains("unable to resolve host") ||
                lower.contains("failed to connect") ||
                lower.contains("network is unreachable") ||
                lower.contains("connection refused") ||
                lower.contains("timeout") ||
                lower.contains("timed out") -> "PRACTICAL_WIFI_REQUIRED"
            lower.contains("521") ||
                lower.contains("522") ||
                lower.contains("502") ||
                lower.contains("503") ||
                lower.contains("504") -> "PRACTICAL_SERVER_DOWN"
            else -> message
        }
    }

    private fun showPracticalPasswordPrompt() {
        val input = EditText(this).apply {
            hint = "Practical password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("")
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Practical Password Needed")
            .setMessage(
                "Your practical account did not accept the default password.\n\n" +
                    "Enter your own practical/LAS password to continue."
            )
            .setView(container)
            .setPositiveButton("Login") { _, _ ->
                val password = input.text.toString().trim()
                if (password.isBlank()) {
                    Toast.makeText(this, "Password is required", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.retryLoginWithPassword(password)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWifiInstructionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Practical Server Not Reachable")
            .setMessage(
                "The practical server could not be reached.\n\n" +
                    "Try this:\n" +
                    "1. Connect to the college Wi-Fi.\n" +
                    "2. Turn off VPN or private DNS if enabled.\n" +
                    "3. Open the Practicals section again after the Wi-Fi connects.\n\n" +
                    "Note: even on college Wi-Fi, ChatGPT-based AI help may be slow or blocked because of campus network restrictions."
            )
            .setPositiveButton("Retry") { _, _ -> viewModel.retryLoginAndLoadSubjects() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showServerDownDialog() {
        AlertDialog.Builder(this)
            .setTitle("Practical Server Down")
            .setMessage(
                "The college practical server appears to be down or not responding right now.\n\n" +
                    "Try this:\n" +
                    "1. Make sure you are on college Wi-Fi first.\n" +
                    "2. Wait a few minutes and retry.\n" +
                    "3. If it still fails, the LAS server is likely down for everyone.\n\n" +
                    "ChatGPT AI helpers can also be slow or blocked on college Wi-Fi due to campus restrictions."
            )
            .setPositiveButton("Retry") { _, _ -> viewModel.retryLoginAndLoadSubjects() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPracticalHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Practical Help")
            .setMessage(
                "Recommended workflow:\n\n" +
                    "1. First connect to college Wi-Fi and open Practicals.\n" +
                    "2. Let the app load all subjects and practical lists. This helps cache practical data for offline use.\n" +
                    "3. After that, you can work on theory, code, output, conclusion, and local drafts on cellular data or fully offline.\n" +
                    "4. When you are ready to sync server drafts or submit, reconnect to college Wi-Fi and upload from there.\n\n" +
                    "Offline support is available:\n" +
                    "- cached practical subjects and lists can open without the server\n" +
                    "- local practical drafts are saved on the device\n\n" +
                    "Important notes:\n" +
                    "- default practical login may not work for everyone, so you can enter your own practical password if needed\n" +
                    "- ChatGPT AI help may be slow or blocked on college Wi-Fi because of campus restrictions"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_SUBJECT_ID   = "subject_id"
        const val EXTRA_SUBJECT_NAME = "subject_name"
    }

    // ── Adapter ────────────────────────────────────────────────────────────────

    private class SubjectAdapter(
        private val onClick: (PracticalSubject) -> Unit
    ) : ListAdapter<PracticalSubject, SubjectAdapter.VH>(DIFF_CALLBACK) {

        init {
            setHasStableIds(true)
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvSubjectName)
            val code: TextView = view.findViewById(R.id.tvSubjectCode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_practical_subject, parent, false)
        )

        override fun getItemId(position: Int): Long = getItem(position).id.toLong()

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.itemView.rotation = when (position % 6) {
                0 -> -1.2f
                1 -> 0.9f
                2 -> 0.55f
                3 -> -0.95f
                4 -> -0.45f
                else -> 1.15f
            }
            holder.itemView.translationX = when (position % 4) {
                0 -> -3f
                1 -> 4f
                2 -> 2f
                else -> -4f
            }
            holder.itemView.translationY = when (position % 4) {
                0 -> 0f
                1 -> 8f
                2 -> -5f
                else -> 6f
            }
            holder.name.text = item.subjectname
            holder.code.text = item.subjectcode
            holder.itemView.setOnClickListener { onClick(item) }
        }

        companion object {
            private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PracticalSubject>() {
                override fun areItemsTheSame(oldItem: PracticalSubject, newItem: PracticalSubject): Boolean =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: PracticalSubject, newItem: PracticalSubject): Boolean =
                    oldItem == newItem
            }
        }
    }
}
