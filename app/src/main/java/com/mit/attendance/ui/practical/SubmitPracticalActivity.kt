package com.mit.attendance.ui.practical

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences

class SubmitPracticalActivity : AppCompatActivity() {

    private val viewModel: PracticalViewModel by viewModels {
        PracticalViewModelFactory(application, UserPreferences(this))
    }

    // Views
    private lateinit var tvAim:           TextView
    private lateinit var tvDesc:          TextView
    private lateinit var btnCopyAll:      Button
    private lateinit var btnPasteTheory:  Button
    private lateinit var btnPasteConclusion: Button
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
        btnCopyAll         = findViewById(R.id.btnCopyAll)
        btnPasteTheory     = findViewById(R.id.btnPasteTheory)
        btnPasteConclusion = findViewById(R.id.btnPasteConclusion)
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
            tvDesc.visibility = View.GONE
        } else {
            tvDesc.text = practicalDesc
        }

        if (existingTheory.isNotBlank())     etTheory.setText(existingTheory)
        if (existingConclusion.isNotBlank()) etConclusion.setText(existingConclusion)

        btnSubmit.text = if (isSubmitted) "Update Practical" else "Submit Practical"

        // ── Clipboard Buttons ─────────────────────────────────────────────
        btnCopyAll.setOnClickListener {
            val combinedText = if (practicalDesc.isNotBlank()) {
                "Aim: $practicalAim\n\nDescription: $practicalDesc"
            } else {
                "Aim: $practicalAim"
            }
            copyToClipboard("Practical Details", combinedText)
        }
        
        btnPasteTheory.setOnClickListener { pasteToEditText(etTheory, "Theory") }
        btnPasteConclusion.setOnClickListener { pasteToEditText(etConclusion, "Conclusion") }

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

        // ── Draft Handling ────────────────────────────────────────────────
        
        // Auto-save on change
        etTheory.addTextChangedListener { saveCurrentDraft() }
        etConclusion.addTextChangedListener { saveCurrentDraft() }

        viewModel.draftState.observe(this) { draft ->
            if (draft != null) {
                // Only load if the current fields are empty (don't overwrite what they're typing)
                if (etTheory.text.isBlank() && draft.theory.isNotBlank()) {
                    etTheory.setText(draft.theory)
                }
                if (etConclusion.text.isBlank() && draft.conclusion.isNotBlank()) {
                    etConclusion.setText(draft.conclusion)
                }
            }
        }

        // Load existing draft
        viewModel.loadDraft(practicalId)

        // Login is needed here too (in case activity is resumed fresh)
        if (viewModel.loginState.value !is UiState.Success) {
            viewModel.init()
        }
    }

    private fun saveCurrentDraft() {
        val theory = etTheory.text.toString()
        val conclusion = etConclusion.text.toString()
        if (theory.isNotBlank() || conclusion.isNotBlank()) {
            viewModel.saveDraft(practicalId, theory, conclusion)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Clear Draft")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            AlertDialog.Builder(this)
                .setTitle("Clear Draft?")
                .setMessage("This will remove your saved theory and conclusion for this practical.")
                .setPositiveButton("Clear") { _, _ ->
                    etTheory.setText("")
                    etConclusion.setText("")
                    viewModel.deleteDraft(practicalId)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun pasteToEditText(editText: EditText, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasteData = item?.text
            if (pasteData != null) {
                editText.setText(pasteData)
                Toast.makeText(this, "Pasted into $label", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty or not text", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}