package com.mit.attendance.ui.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.BuildConfig
import com.mit.attendance.agent.AgentRequestAuth
import com.mit.attendance.databinding.ActivityQwenAuthBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class QwenAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQwenAuthBinding
    private val client = OkHttpClient()
    private var pollJob: Job? = null

    private val email: String by lazy {
        intent.getStringExtra(EXTRA_EMAIL).orEmpty()
    }

    private val verificationUrl: String by lazy {
        intent.getStringExtra(EXTRA_VERIFICATION_URL).orEmpty()
    }

    private val authStatusUrl: String by lazy {
        BuildConfig.QWEN_WS_URL
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQwenAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (email.isBlank() || verificationUrl.isBlank()) {
            Toast.makeText(this, "Missing Qwen authentication details.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        configureWebView(binding.webView)
        binding.webView.loadUrl(verificationUrl)
    }

    override fun onStart() {
        super.onStart()
        startPolling()
    }

    override fun onStop() {
        pollJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.hide()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@QwenAuthActivity,
                        error?.description ?: "Failed to load Qwen login page.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                val authenticated = runCatching { isAuthenticated(email) }.getOrDefault(false)
                if (authenticated) {
                    setResult(RESULT_OK)
                    finish()
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun isAuthenticated(email: String): Boolean {
        val request = Request.Builder()
            .url("$authStatusUrl/auth/status?email=${android.net.Uri.encode(email)}&${AgentRequestAuth.buildQuery(email)}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string().orEmpty()
            return JSONObject(body).optBoolean("authenticated")
        }
    }

    companion object {
        private const val EXTRA_EMAIL = "extra_email"
        private const val EXTRA_VERIFICATION_URL = "extra_verification_url"
        private const val POLL_INTERVAL_MS = 2_000L

        fun createIntent(context: Context, email: String, verificationUrl: String): Intent =
            Intent(context, QwenAuthActivity::class.java).apply {
                putExtra(EXTRA_EMAIL, email)
                putExtra(EXTRA_VERIFICATION_URL, verificationUrl)
            }
    }
}
