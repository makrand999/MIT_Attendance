package com.mit.attendance.ui.practical

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences

class SubmitPracticalActivity : AppCompatActivity() {

    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(UserPreferences(this))
    }

    // Views
    private lateinit var tvAim:           TextView
    private lateinit var tvDesc:          TextView
    private lateinit var tvDescLabel:     TextView
    private lateinit var etTheory:        EditText
    private lateinit var etConclusion:    EditText
    private lateinit var btnAiTheory:     Button
    private lateinit var btnAiConclusion: Button
    private lateinit var btnSubmit:       Button
    private lateinit var progressTheory:  ProgressBar
    private lateinit var progressConclusion: ProgressBar
    private lateinit var progressSubmit:  ProgressBar
    private lateinit var tvSubmitStatus:  TextView

    // Extras
    private var subjectId       = 0
    private var practicalId     = 0
    private var practicalNumber = 0
    private var practicalAim    = ""
    private var practicalDesc   = ""
    private var isSubmitted     = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_submit_practical)

        // Read extras
        subjectId       = intent.getIntExtra(PracticalActivity.EXTRA_SUBJECT_ID,       0)
        practicalId     = intent.getIntExtra("practical_id",     0)
        practicalNumber = intent.getIntExtra("practical_number", 0)
        practicalAim    = intent.getStringExtra("practical_title") ?: ""
        practicalDesc   = intent.getStringExtra("practical_desc") ?: ""
        isSubmitted     = intent.getBooleanExtra("is_submitted",  false)
        val existingTheory  = intent.getStringExtra("existing_theory") ?: ""
        val existingConclusion = intent.getStringExtra("existing_concl") ?: ""

        supportActionBar?.apply {
            title = "Practical $practicalNumber"
            setDisplayHomeAsUpEnabled(true)
        }

        // Bind views
        tvAim              = findViewById(R.id.tvAim)
        tvDesc             = findViewById(R.id.tvDesc)
        tvDescLabel        = findViewById(R.id.tvDescLabel)
        etTheory           = findViewById(R.id.etTheory)
        etConclusion       = findViewById(R.id.etConclusion)
        btnAiTheory        = findViewById(R.id.btnAiTheory)
        btnAiConclusion    = findViewById(R.id.btnAiConclusion)
        btnSubmit          = findViewById(R.id.btnSubmit)
        progressTheory     = findViewById(R.id.progressTheory)
        progressConclusion = findViewById(R.id.progressConclusion)
        progressSubmit     = findViewById(R.id.progressSubmit)
        tvSubmitStatus     = findViewById(R.id.tvSubmitStatus)

        // Populate
        tvAim.text = practicalAim
        if (practicalDesc.isBlank()) {
            tvDescLabel.visibility = View.GONE
            tvDesc.visibility      = View.GONE
        } else {
            tvDesc.text = practicalDesc
        }

        if (existingTheory.isNotBlank())     etTheory.setText(existingTheory)
        if (existingConclusion.isNotBlank()) etConclusion.setText(existingConclusion)

        btnSubmit.text = if (isSubmitted) "Update Practical" else "Submit Practical"

        // ── AI: Theory ────────────────────────────────────────────────────
        btnAiTheory.setOnClickListener {
            if (etTheory.text.isNotBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Replace Theory?")
                    .setMessage("AI-generated content will replace your current theory. Continue?")
                    .setPositiveButton("Replace") { _, _ -> viewModel.generateTheory(practicalAim, practicalDesc.ifBlank { null }) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                viewModel.generateTheory(practicalAim, practicalDesc.ifBlank { null })
            }
        }

        viewModel.theoryAiState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    progressTheory.visibility = View.VISIBLE
                    btnAiTheory.isEnabled = false
                }
                is UiState.Success -> {
                    progressTheory.visibility = View.GONE
                    btnAiTheory.isEnabled = true
                    etTheory.setText(state.data)
                    etTheory.setSelection(0) // scroll to top
                    viewModel.resetTheoryAiState()
                }
                is UiState.Error -> {
                    progressTheory.visibility = View.GONE
                    btnAiTheory.isEnabled = true
                    Toast.makeText(this, "AI error: ${state.message}", Toast.LENGTH_LONG).show()
                    viewModel.resetTheoryAiState()
                }
                else -> {
                    progressTheory.visibility = View.GONE
                    btnAiTheory.isEnabled = true
                }
            }
        }

        // ── AI: Conclusion ────────────────────────────────────────────────
        btnAiConclusion.setOnClickListener {
            if (etConclusion.text.isNotBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Replace Conclusion?")
                    .setMessage("AI-generated content will replace your current conclusion. Continue?")
                    .setPositiveButton("Replace") { _, _ -> viewModel.generateConclusion(practicalAim, practicalDesc.ifBlank { null }) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                viewModel.generateConclusion(practicalAim, practicalDesc.ifBlank { null })
            }
        }

        viewModel.conclusionAiState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    progressConclusion.visibility = View.VISIBLE
                    btnAiConclusion.isEnabled = false
                }
                is UiState.Success -> {
                    progressConclusion.visibility = View.GONE
                    btnAiConclusion.isEnabled = true
                    etConclusion.setText(state.data)
                    etConclusion.setSelection(0)
                    viewModel.resetConclusionAiState()
                }
                is UiState.Error -> {
                    progressConclusion.visibility = View.GONE
                    btnAiConclusion.isEnabled = true
                    Toast.makeText(this, "AI error: ${state.message}", Toast.LENGTH_LONG).show()
                    viewModel.resetConclusionAiState()
                }
                else -> {
                    progressConclusion.visibility = View.GONE
                    btnAiConclusion.isEnabled = true
                }
            }
        }

        // ── Submit ────────────────────────────────────────────────────────
        btnSubmit.setOnClickListener {
            val theory     = etTheory.text.toString().trim()
            val conclusion = etConclusion.text.toString().trim()
            if (theory.isBlank()) {
                etTheory.error = "Theory is required"
                etTheory.requestFocus()
                return@setOnClickListener
            }
            if (conclusion.isBlank()) {
                etConclusion.error = "Conclusion is required"
                etConclusion.requestFocus()
                return@setOnClickListener
            }
            viewModel.submitPractical(subjectId, practicalId, practicalNumber, theory, conclusion)
        }

        viewModel.submitState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    progressSubmit.visibility = View.VISIBLE
                    btnSubmit.isEnabled = false
                    tvSubmitStatus.visibility = View.GONE
                }
                is UiState.Success -> {
                    progressSubmit.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    tvSubmitStatus.visibility = View.VISIBLE
                    tvSubmitStatus.text = "✅ Submitted successfully!"
                    tvSubmitStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
                    btnSubmit.text = "Update Practical"
                    viewModel.resetSubmitState()
                }
                is UiState.Error -> {
                    progressSubmit.visibility = View.GONE
                    btnSubmit.isEnabled = true
                    tvSubmitStatus.visibility = View.VISIBLE
                    tvSubmitStatus.text = "❌ ${state.message}"
                    tvSubmitStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
                    viewModel.resetSubmitState()
                }
                else -> {
                    progressSubmit.visibility = View.GONE
                    btnSubmit.isEnabled = true
                }
            }
        }

        // Login is needed here too (in case activity is resumed fresh)
        if (viewModel.loginState.value !is UiState.Success) {
            viewModel.init()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
