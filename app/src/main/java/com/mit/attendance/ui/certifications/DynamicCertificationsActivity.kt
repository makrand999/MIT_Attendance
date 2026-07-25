package com.mit.attendance.ui.certifications

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mit.attendance.BuildConfig
import com.mit.attendance.R
import com.mit.attendance.databinding.ActivityDynamicCertificationsBinding
import com.mit.attendance.model.CertificationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class DynamicCertificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDynamicCertificationsBinding
    private lateinit var adapter: DynamicCertificationsAdapter
    private var progressDialog: AlertDialog? = null
    private var progressView: View? = null
    private var automationJob: Job? = null
    
    private val client = OkHttpClient()
    private val githubToken = BuildConfig.GITHUB_TOKEN_MAIN
    private val GIST_URL = "https://gist.githubusercontent.com/makarand-shinde/a2a6794e8c343e2554893c2d1141cd6e/raw"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDynamicCertificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dynamic Certs (BETA)"

        setupRecyclerView()
        loadCertifications()

        binding.swipeRefresh.setOnRefreshListener {
            loadCertifications()
        }
    }

    private fun setupRecyclerView() {
        adapter = DynamicCertificationsAdapter { item ->
            showActionDialog(item)
        }
        binding.rvCertifications.layoutManager = LinearLayoutManager(this)
        binding.rvCertifications.adapter = adapter
    }

    private fun loadCertifications() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GIST_URL)
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (!response.isSuccessful) throw Exception("Failed to fetch gist: ${response.code}")

                val json = JSONObject(body)
                val scripts = json.getJSONArray("scripts")
                val items = mutableListOf<CertificationItem>()

                for (i in 0 until scripts.length()) {
                    val obj = scripts.getJSONObject(i)
                    items.add(CertificationItem(
                        id = obj.getString("entry"),
                        title = obj.getString("name"),
                        subtitle = obj.getString("description"),
                        platform = "Github",
                        type = "python",
                        pythonScriptUrl = obj.getString("download_url")
                    ))
                }

                withContext(Dispatchers.Main) {
                    adapter.submitList(items)
                    binding.swipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                Log.e("DynamicCerts", "Load failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DynamicCertificationsActivity, "Failed to load: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun showActionDialog(item: CertificationItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage("This will download the Python script and execute it using your saved session cookies.")
            .setPositiveButton("Run Automation") { _, _ ->
                startAutomation(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAutomation(item: CertificationItem) {
        val scriptUrl = item.pythonScriptUrl ?: return
        
        showProgressDialog()
        
        automationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Convert github web URL to raw URL if needed
                val rawUrl = if (scriptUrl.contains("github.com") && scriptUrl.contains("/blob/")) {
                    scriptUrl.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
                } else scriptUrl

                // 2. Download script with token
                val request = Request.Builder()
                    .url(rawUrl)
                    .header("Authorization", "token $githubToken")
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
                
                val cacheDir = File(cacheDir, "automation_scripts")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val scriptFile = File(cacheDir, item.id)
                FileOutputStream(scriptFile).use { it.write(response.body?.bytes()) }

                // 3. Execute using Python Engine
                withContext(Dispatchers.Main) {
                    val engine = PythonAutomationEngine(this@DynamicCertificationsActivity)
                    engine.execute(scriptUrl = null, localFile = scriptFile, callback = object : PythonAutomationEngine.Callback {
                        override fun onStepUpdate(step: Int, total: Int, message: String) {
                            runOnUiThread { updateProgress(step, total, message) }
                        }
                        override fun onComplete(success: Boolean, message: String) {
                            runOnUiThread {
                                progressDialog?.dismiss()
                                if (automationJob?.isActive == true) {
                                    showResult(success, message)
                                }
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    showResult(false, e.message ?: "Download failed")
                }
            }
        }
    }

    private fun showProgressDialog() {
        progressView = LayoutInflater.from(this).inflate(R.layout.dialog_cert_progress, null)
        
        val btnCancel = progressView?.findViewById<Button>(R.id.btnCancel)
        btnCancel?.setOnClickListener {
            stopAutomation()
        }

        progressDialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun stopAutomation() {
        automationJob?.cancel()
        automationJob = null
        progressDialog?.dismiss()
        Toast.makeText(this, "Automation cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun updateProgress(step: Int, totalSteps: Int, message: String) {
        progressView?.let { view ->
            val tvMessage = view.findViewById<TextView>(R.id.tvProgressMessage)
            val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
            val tvStep = view.findViewById<TextView>(R.id.tvStepCount)

            tvMessage?.text = message
            tvStep?.text = "STEP $step OF $totalSteps"
            
            progressBar?.visibility = View.VISIBLE
            progressBar?.max = totalSteps
            progressBar?.setProgress(step, true)
        }
    }

    private fun showResult(success: Boolean, message: String) {
        AlertDialog.Builder(this)
            .setTitle(if (success) "Completed" else "Automation Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
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
        super.onDestroy()
        automationJob?.cancel()
    }
}
