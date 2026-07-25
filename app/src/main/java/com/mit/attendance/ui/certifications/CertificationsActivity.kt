package com.mit.attendance.ui.certifications

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityCertificationsBinding
import com.mit.attendance.ui.microsoft.*
import com.mit.attendance.ui.subjects.GenericWebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CertificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCertificationsBinding
    private var progressDialog: AlertDialog? = null
    private var progressView: View? = null
    private var isJobRunning = false
    private lateinit var prefs: UserPreferences
    private var progressStartedAtMs = 0L
    private var progressTimerJob: Job? = null
    private var currentProgressDetail = "Preparing details..."
    private var lastFailureDetail: String? = null
    private val proxyFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch {
            try {
                val rawText = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw IllegalStateException("Failed to read selected file")

                val proxies = CertificationProxyParser.parse(rawText)
                if (proxies.isEmpty()) {
                    Toast.makeText(
                        this@CertificationsActivity,
                        "No valid proxies found in the selected file.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                prefs.saveCertificationProxies(proxies)
                Toast.makeText(
                    this@CertificationsActivity,
                    "Imported ${proxies.size} proxies.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@CertificationsActivity,
                    e.message ?: "Failed to import proxies.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    private val csharpMsCookieFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch {
            try {
                val rawText = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw IllegalStateException("Failed to read selected file")

                val imported = MicrosoftCookieManager.importCSharpCookies(this@CertificationsActivity, rawText)
                updateCSharpCookieStatus()
                Toast.makeText(
                    this@CertificationsActivity,
                    "Imported $imported C# Microsoft cookies.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@CertificationsActivity,
                    e.message ?: "Failed to import C# Microsoft cookies.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCertificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = UserPreferences(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Certifications"

        binding.cardDynamicCerts.setOnClickListener {
            startActivity(Intent(this, DynamicCertificationsActivity::class.java))
        }

        binding.cardImportProxies.setOnClickListener {
            proxyFilePicker.launch("text/plain")
        }

        binding.cardImportCSharpMsCookies.setOnClickListener {
            csharpMsCookieFilePicker.launch("application/json")
        }

        binding.cardWebBrowser.setOnClickListener {
            showBrowserDialog()
        }

        binding.cardMicrosoft.setOnClickListener {
            startActivity(Intent(this, MicrosoftLearnActivity::class.java))
        }

        binding.cardFcc.setOnClickListener {
            val intent = Intent(this, MicrosoftLearnActivity::class.java)
            intent.putExtra("target_url", "https://www.freecodecamp.org/learn")
            startActivity(intent)
        }

        binding.cardCSharpCert.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startCSharpCertification()
        }

        binding.cardResponsiveWeb.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startGenericFccCertification(listOf("responsive-web-design-v9"), "Responsive Web Design")
        }

        binding.cardJavaScript.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startGenericFccCertification(
                listOf("javascript-v9", "javascript-algorithms-and-data-structures-v8"),
                "JavaScript"
            )
        }

        binding.cardFrontEnd.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startGenericFccCertification(
                listOf("front-end-development-libraries-v9"),
                "Front-End Development"
            )
        }

        binding.cardRelationalDb.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startGenericFccCertification(
                listOf("relational-databases-v9"),
                "Relational Databases"
            )
        }

        binding.cardBackendApis.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startGenericFccCertification(
                listOf("back-end-development-and-apis-v9"),
                "Back-End Development"
            )
        }

        binding.cardPython.setOnClickListener {
            if (isJobRunning) return@setOnClickListener
            startGenericFccCertification(
                listOf("python-v9"),
                "Python"
            )
        }

        lifecycleScope.launch {
            prefs.bgNavUri.collectLatest { uri ->
                if (uri != null) {
                    binding.ivBackground.setImageURI(uri.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_nav)
                }
            }
        }

        lifecycleScope.launch {
            prefs.certificationProxies.collectLatest { proxies ->
                binding.tvProxyImportStatus.text = if (proxies.isEmpty()) {
                    "No proxy list imported"
                } else {
                    "${proxies.size} proxies loaded"
                }
            }
        }

        updateCSharpCookieStatus()
    }

    private fun updateCSharpCookieStatus() {
        binding.tvCSharpMsCookieStatus.text = if (MicrosoftCookieManager.isCSharpDefaultPresent(this)) {
            "C# transcript account ready"
        } else {
            "No C# transcript account imported"
        }
    }

    private fun showBrowserDialog() {
        val editText = EditText(this).apply {
            setText("https://www.google.com")
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Web Browser")
            .setMessage("Enter URL to open:")
            .setView(editText)
            .setPositiveButton("Open") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    val intent = Intent(this, GenericWebViewActivity::class.java).apply {
                        putExtra("EXTRA_URL", url)
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCSharpCertification() {
        val msLoggedIn = MicrosoftCookieManager.isDocsTokenPresent(this) ||
            MicrosoftCookieManager.isCSharpDefaultPresent(this)

        lifecycleScope.launch {
            FccCookieManager.syncCookies(this@CertificationsActivity)
            val fccToken = FccCookieManager.getCookie(this@CertificationsActivity, "jwt_access_token")

            if (!msLoggedIn) {
                showLoginRequiredDialog("Microsoft Learn") {
                    startActivity(Intent(this@CertificationsActivity, MicrosoftLearnActivity::class.java))
                }
                return@launch
            }

            if (fccToken == null) {
                showLoginRequiredDialog("FreeCodeCamp") {
                    val intent = Intent(this@CertificationsActivity, MicrosoftLearnActivity::class.java)
                    intent.putExtra("target_url", "https://www.freecodecamp.org/learn")
                    startActivity(intent)
                }
                return@launch
            }

            showProgressDialog(6)
            isJobRunning = true

            val completer = CSharpCertCompleter(applicationContext)
            completer.start(object : CSharpCertCompleter.Callback {
                override fun onStepUpdate(step: Int, message: String, progress: Int, total: Int) {
                    runOnUiThread {
                        updateProgress(step, 6, message, progress, total)
                    }
                }

                override fun onDetailUpdate(message: String, isError: Boolean) {
                    runOnUiThread {
                        updateProgressDetail(message, isError)
                    }
                }

                override fun onComplete(success: Boolean, message: String) {
                    runOnUiThread {
                        isJobRunning = false
                        stopProgressTimer()
                        progressDialog?.dismiss()
                        showResult(success, buildCompletionMessage(message, success))
                    }
                }
            })
        }
    }

    private fun startGenericFccCertification(superBlocks: List<String>, certName: String) {
        val fccToken = FccCookieManager.getCookie(this, "jwt_access_token")

        if (fccToken == null) {
            showLoginRequiredDialog("FreeCodeCamp") {
                val intent = Intent(this, MicrosoftLearnActivity::class.java)
                intent.putExtra("target_url", "https://www.freecodecamp.org/learn")
                startActivity(intent)
            }
            return
        }

        showProgressDialog(3)
        isJobRunning = true

        val completer = GenericFccCertCompleter(applicationContext, superBlocks, certName)
        lifecycleScope.launch {
            completer.start(object : GenericFccCertCompleter.Callback {
                override fun onStepUpdate(step: Int, message: String, progress: Int, total: Int) {
                    runOnUiThread {
                        updateProgress(step, 3, message, progress, total)
                    }
                }

                override fun onDetailUpdate(message: String, isError: Boolean) {
                    runOnUiThread {
                        updateProgressDetail(message, isError)
                    }
                }

                override fun onComplete(success: Boolean, message: String) {
                    runOnUiThread {
                        isJobRunning = false
                        stopProgressTimer()
                        progressDialog?.dismiss()
                        showResult(success, buildCompletionMessage(message, success))
                    }
                }
            })
        }
    }

    private fun showProgressDialog(totalSteps: Int) {
        progressView = LayoutInflater.from(this).inflate(R.layout.dialog_cert_progress, null)
        progressStartedAtMs = SystemClock.elapsedRealtime()
        currentProgressDetail = "Preparing details..."
        lastFailureDetail = null
        progressTimerJob?.cancel()
        progressDialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog?.show()
        progressView?.findViewById<TextView>(R.id.tvCurrentPart)?.text = currentProgressDetail
        progressView?.findViewById<TextView>(R.id.tvLastFailure)?.visibility = View.GONE
        progressView?.findViewById<TextView>(R.id.tvElapsed)?.text = "Elapsed: 00:00"
        updateProgress(1, totalSteps, "Preparing...", -1, -1)
        progressTimerJob = lifecycleScope.launch {
            while (progressDialog?.isShowing == true) {
                val elapsed = SystemClock.elapsedRealtime() - progressStartedAtMs
                progressView?.findViewById<TextView>(R.id.tvElapsed)?.text =
                    "Elapsed: ${formatElapsed(elapsed)}"
                delay(1000)
            }
        }
    }

    private fun updateProgress(step: Int, totalSteps: Int, message: String, progress: Int, total: Int) {
        progressView?.let { view ->
            val tvMessage = view.findViewById<TextView>(R.id.tvProgressMessage)
            val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
            val tvStep = view.findViewById<TextView>(R.id.tvStepCount)

            tvMessage?.text = message
            tvStep?.text = "STEP $step OF $totalSteps"
            if (currentProgressDetail == "Preparing details..." || currentProgressDetail == tvMessage?.text) {
                currentProgressDetail = message
                view.findViewById<TextView>(R.id.tvCurrentPart)?.text = message
            }
            
            if (progress != -1 && total != -1) {
                progressBar?.visibility = View.VISIBLE
                progressBar?.max = total
                progressBar?.setProgress(progress, true)
            } else {
                progressBar?.visibility = View.GONE
            }
        }
    }

    private fun updateProgressDetail(message: String, isError: Boolean) {
        progressView?.let { view ->
            if (isError) {
                lastFailureDetail = message
                view.findViewById<TextView>(R.id.tvLastFailure)?.apply {
                    text = message
                    visibility = View.VISIBLE
                }
            } else {
                currentProgressDetail = message
                view.findViewById<TextView>(R.id.tvCurrentPart)?.text = message
            }
        }
    }

    private fun buildCompletionMessage(message: String, success: Boolean): String {
        val elapsed = if (progressStartedAtMs == 0L) null else formatElapsed(SystemClock.elapsedRealtime() - progressStartedAtMs)
        return buildString {
            append(message)
            if (!success && lastFailureDetail != null && !message.contains(lastFailureDetail!!)) {
                append("\n\nFailed part: ")
                append(lastFailureDetail)
            }
            if (elapsed != null) {
                append("\n\nElapsed: ")
                append(elapsed)
            }
        }
    }

    private fun stopProgressTimer() {
        progressTimerJob?.cancel()
        progressTimerJob = null
    }

    private fun formatElapsed(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun showResult(success: Boolean, message: String) {
        AlertDialog.Builder(this)
            .setTitle(if (success) "Success" else "Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLoginRequiredDialog(platform: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Login Required")
            .setMessage("Please log in to $platform first.")
            .setPositiveButton("Go to Login") { _, _ -> onOk() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        stopProgressTimer()
        super.onDestroy()
    }
}
