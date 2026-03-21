package com.mit.attendance.ui.certifications

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityCertificationsBinding
import com.mit.attendance.ui.microsoft.CSharpCertCompleter
import com.mit.attendance.ui.microsoft.FccCookieManager
import com.mit.attendance.ui.microsoft.MicrosoftCookieManager
import com.mit.attendance.ui.microsoft.MicrosoftLearnActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CertificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCertificationsBinding
    private var progressDialog: AlertDialog? = null
    private var progressView: View? = null
    private var isJobRunning = false
    private lateinit var prefs: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCertificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = UserPreferences(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Certifications"

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

        lifecycleScope.launch {
            prefs.bgNavUri.collectLatest { uri ->
                if (uri != null) {
                    binding.ivBackground.setImageURI(uri.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_nav)
                }
            }
        }
    }

    private fun startCSharpCertification() {
        val msLoggedIn = MicrosoftCookieManager.isDocsTokenPresent(this)
        val fccToken = FccCookieManager.getCookie(this, "jwt_access_token")

        if (!msLoggedIn) {
            showLoginRequiredDialog("Microsoft Learn") {
                startActivity(Intent(this, MicrosoftLearnActivity::class.java))
            }
            return
        }

        if (fccToken == null) {
            showLoginRequiredDialog("FreeCodeCamp") {
                val intent = Intent(this, MicrosoftLearnActivity::class.java)
                intent.putExtra("target_url", "https://www.freecodecamp.org/learn")
                startActivity(intent)
            }
            return
        }

        showProgressDialog()
        isJobRunning = true

        val completer = CSharpCertCompleter(applicationContext)
        lifecycleScope.launch {
            completer.start(object : CSharpCertCompleter.Callback {
                override fun onStepUpdate(step: Int, message: String, progress: Int, total: Int) {
                    runOnUiThread {
                        updateProgress(step, message, progress, total)
                    }
                }

                override fun onComplete(success: Boolean, message: String) {
                    runOnUiThread {
                        isJobRunning = false
                        progressDialog?.dismiss()
                        showResult(success, message)
                    }
                }
            })
        }
    }

    private fun showProgressDialog() {
        progressView = LayoutInflater.from(this).inflate(R.layout.dialog_cert_progress, null)
        progressDialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun updateProgress(step: Int, message: String, progress: Int, total: Int) {
        progressView?.let { view ->
            val tvMessage = view.findViewById<TextView>(R.id.tvProgressMessage)
            val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
            val tvStep = view.findViewById<TextView>(R.id.tvStepCount)

            tvMessage?.text = message
            tvStep?.text = "STEP $step OF 6"
            
            if (progress != -1 && total != -1) {
                progressBar?.visibility = View.VISIBLE
                progressBar?.max = total
                progressBar?.setProgress(progress, true)
            } else {
                progressBar?.visibility = View.GONE
            }
        }
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
}
