package com.mit.attendance.ui.microsoft

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.databinding.ActivityMicrosoftLearnBinding
import kotlinx.coroutines.launch

class MicrosoftLearnActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicrosoftLearnBinding
    private var loginDialog: AlertDialog? = null

    private var currentUid: String? = null
    private var currentType: String? = null // "path" or "module"
    private var isJobRunning = false
    private var isFccMode = false
    private var hasShownLoginPrompt = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicrosoftLearnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val targetUrl = intent.getStringExtra("target_url")
        isFccMode = targetUrl?.contains("freecodecamp") == true

        // Floating back button (Bottom Left)
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnComplete.setOnClickListener {
            startCompletionJob()
        }

        setupWebView(targetUrl)
        setupSwipeRefresh()

        // System back button: Navigate back page-by-page in WebView
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary, R.color.accent)
        
        // Prevent SwipeRefreshLayout from intercepting touches when WebView is scrolled
        binding.webView.viewTreeObserver.addOnScrollChangedListener {
            binding.swipeRefresh.isEnabled = binding.webView.scrollY == 0
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(initialUrl: String?) {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Use a clean mobile User Agent to ensure FCC renders the correct mobile UI
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }

        binding.webView.requestFocus()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        lifecycleScope.launch {
            if (isFccMode) {
                FccCookieManager.injectCookies(this@MicrosoftLearnActivity)
                binding.webView.loadUrl(initialUrl ?: "https://www.freecodecamp.org/learn")
            } else {
                MicrosoftCookieManager.injectCookies(this@MicrosoftLearnActivity)
                binding.webView.loadUrl(initialUrl ?: "https://learn.microsoft.com/en-gb/training/")
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                lifecycleScope.launch {
                    if (isFccMode) {
                        FccCookieManager.syncCookies(this@MicrosoftLearnActivity)
                    } else {
                        MicrosoftCookieManager.syncCookies(this@MicrosoftLearnActivity)
                        checkLoginStatus()
                        detectUrl(url)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
    }

    private fun detectUrl(url: String?) {
        if (isFccMode || url == null) return

        val pathPattern = "/training/paths/"
        val modulePattern = "/training/modules/"

        when {
            url.contains(pathPattern) -> {
                currentType = "path"
                extractUidFromMetaOrUrl(url, pathPattern)
            }
            url.contains(modulePattern) -> {
                currentType = "module"
                extractUidFromMetaOrUrl(url, modulePattern)
            }
            else -> {
                currentType = null
                currentUid = null
                updateButtonState()
            }
        }
    }

    private fun extractUidFromMetaOrUrl(url: String, pattern: String) {
        val js = """
            (function() {
                const metaModule = document.querySelector('meta[name="module_uid"]');
                if (metaModule) return metaModule.content;
                const metaUid = document.querySelector('meta[name="uid"]');
                return metaUid ? metaUid.content : null;
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js) { uidJson ->
            val cleanedUid = uidJson?.trim()?.removeSurrounding("\"")
            currentUid = if (!cleanedUid.isNullOrEmpty() && cleanedUid != "null") {
                cleanedUid
            } else {
                extractUidFromUrl(url, pattern)
            }
            updateButtonState()
        }
    }

    private fun extractUidFromUrl(url: String, pattern: String): String {
        val start = url.indexOf(pattern) + pattern.length
        var uid = url.substring(start)
        if (uid.contains("?")) uid = uid.substring(0, uid.indexOf("?"))
        if (uid.contains("#")) uid = uid.substring(0, uid.indexOf("#"))
        if (uid.endsWith("/")) uid = uid.substring(0, uid.length - 1)
        
        if (uid.contains("/")) {
            uid = uid.substring(0, uid.indexOf("/"))
        }
        return uid
    }

    private fun checkLoginStatus(): Boolean {
        if (isFccMode) return true
        val isLoggedIn = MicrosoftCookieManager.isDocsTokenPresent(this)
        if (!isLoggedIn) {
            if (!hasShownLoginPrompt) {
                showLoginPrompt()
                hasShownLoginPrompt = true
            }
        } else {
            loginDialog?.dismiss()
        }
        return isLoggedIn
    }

    private fun showLoginPrompt() {
        if (loginDialog?.isShowing == true) return
        loginDialog = AlertDialog.Builder(this)
            .setTitle("Login Required")
            .setMessage("Please log in to Microsoft Learn to use the auto-completer.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateButtonState() {
        if (isFccMode) {
            binding.btnComplete.visibility = View.GONE
            return
        }
        val isActionable = currentType != null && currentUid != null
        
        binding.btnComplete.visibility = if (isActionable) View.VISIBLE else View.GONE
        binding.btnComplete.alpha = if (!isJobRunning) 1.0f else 0.5f
        binding.btnComplete.isClickable = !isJobRunning
    }

    private fun startCompletionJob() {
        val type = currentType ?: return
        val uid = currentUid ?: return

        if (!MicrosoftCookieManager.isDocsTokenPresent(this)) {
            showLoginPrompt()
            return
        }

        isJobRunning = true
        updateButtonState()
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            MicrosoftLearnCompleter.startJob(
                applicationContext,
                type,
                uid,
                object : MicrosoftLearnCompleter.CompletionCallback {
                    override fun onSuccess() {
                        isJobRunning = false
                        binding.progressBar.visibility = View.GONE
                        detectUrl(binding.webView.url)
                        updateButtonState()
                        Snackbar.make(binding.root, "Completed!", Snackbar.LENGTH_LONG).show()
                        
                        lifecycleScope.launch {
                            MicrosoftCookieManager.persistCookies(this@MicrosoftLearnActivity)
                        }
                    }

                    override fun onError(msg: String) {
                        isJobRunning = false
                        binding.progressBar.visibility = View.GONE
                        detectUrl(binding.webView.url)
                        updateButtonState()
                        if (msg == "401") {
                            showLoginPrompt()
                        } else {
                            Snackbar.make(binding.root, "Failed: $msg", Snackbar.LENGTH_LONG).show()
                        }
                    }
                })
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            if (isFccMode) {
                FccCookieManager.persistCookies(this@MicrosoftLearnActivity)
            } else {
                MicrosoftCookieManager.persistCookies(this@MicrosoftLearnActivity)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
