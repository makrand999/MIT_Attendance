package com.mit.attendance.ui.subjects

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityErpWebviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class ExamCellWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExamCellWeb"
    }

    private lateinit var binding: ActivityErpWebviewBinding
    private lateinit var prefs: UserPreferences
    
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    private val myCookieJar = object : CookieJar {
        private val storage = mutableMapOf<String, Cookie>()
        
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { 
                Log.d("ExamCellNetwork", "Server set-cookie: ${it.name}=${it.value}")
                storage[it.name] = it 
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return storage.values.toList()
        }
        
        fun getAllCookies() = storage.values.toList()
        fun clear() = storage.clear()
    }

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor { message ->
            Log.d("ExamCellNetwork", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .cookieJar(myCookieJar)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(request)
            }
            .addNetworkInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErpWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show toolbar and hide custom back button for ExamCell
        binding.toolbar.visibility = View.VISIBLE
        binding.btnBack.visibility = View.GONE

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Exam Cell"

        prefs = UserPreferences(applicationContext)

        setupWebView()
        
        lifecycleScope.launch {
            val storedPayload = prefs.getExamCellPayload()
            val fullBody = storedPayload?.get("fullBody")

            if (!fullBody.isNullOrEmpty()) {
                Log.d(TAG, "Found stored full body payload, attempting background login")
                binding.progressBar.visibility = View.VISIBLE
                val loginSuccess = performBackgroundLogin(fullBody, storedPayload["url"] ?: "https://mitaexamcell.in/Login.aspx")
                binding.progressBar.visibility = View.GONE
                
                if (loginSuccess) {
                    Log.d(TAG, "Background login successful")
                    binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/MainStud.aspx")
                    return@launch
                } else {
                    Log.w(TAG, "Background login with stored payload failed")
                }
            }

            Log.d(TAG, "Loading login page for manual login")
            binding.webView.loadUrl("https://mitaexamcell.in/Login.aspx")
        }

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

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgent
            
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            
            javaScriptCanOpenWindowsAutomatically = true
        }

        binding.webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onLoginAttempt(body: String, url: String) {
                lifecycleScope.launch {
                    Log.d(TAG, "Captured login request body. Saving to disk...")
                    prefs.saveExamCellFullBody(body, url)
                }
            }
        }, "LoginInterceptor")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Log.d(TAG, "WebView loading: $url")
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "WebView finished: $url")
                binding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()

                if (url?.contains("Login.aspx", ignoreCase = true) == true) {
                    injectLoginInterceptor()
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.e(TAG, "WebView HTTP Error: ${errorResponse?.statusCode} for ${request?.url}")
            }
        }
    }

    private fun injectLoginInterceptor() {
        // Intercept form submission to capture the exact POST body
        val js = """
            (function() {
                var form = document.forms[0];
                if (form) {
                    var originalSubmit = form.submit;
                    form.onsubmit = function() {
                        var formData = new FormData(form);
                        var params = new URLSearchParams();
                        for (var pair of formData.entries()) {
                            params.append(pair[0], pair[1]);
                        }
                        LoginInterceptor.onLoginAttempt(params.toString(), window.location.href);
                        return true;
                    };
                    
                    // Also hook btnLogin click just in case
                    var btn = document.getElementById('btnLogin');
                    if (btn) {
                        btn.addEventListener('click', function() {
                            var formData = new FormData(form);
                            var params = new URLSearchParams();
                            for (var pair of formData.entries()) {
                                params.append(pair[0], pair[1]);
                            }
                            LoginInterceptor.onLoginAttempt(params.toString(), window.location.href);
                        });
                    }
                }
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private suspend fun performBackgroundLogin(fullBody: String, loginUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cookieManager = CookieManager.getInstance()
            withContext(Dispatchers.Main) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                binding.webView.clearCache(true)
            }
            
            myCookieJar.clear()

            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = fullBody.toRequestBody(mediaType)
            
            val req = Request.Builder()
                .url(loginUrl)
                .post(body)
                .header("Referer", loginUrl)
                .header("Origin", "https://mitaexamcell.in")
                .build()
                
            val noRedirectClient = client.newBuilder().followRedirects(false).build()
            val res = noRedirectClient.newCall(req).execute()
            
            Log.d(TAG, "POST Response Code: ${res.code}")
            
            val cookies = myCookieJar.getAllCookies()
            val aspxAuth = cookies.find { it.name == ".ASPXAUTH" }
            
            withContext(Dispatchers.Main) {
                for (cookie in cookies) {
                    val cookieStr = "${cookie.name}=${cookie.value}; Domain=.mitaexamcell.in; Path=/; Secure; SameSite=None"
                    cookieManager.setCookie("https://mitaexamcell.in", cookieStr)
                }
                cookieManager.flush()
            }

            return@withContext res.code == 302 || aspxAuth != null

        } catch (e: Exception) {
            Log.e(TAG, "Error in performBackgroundLogin", e)
            false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_exam_cell, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.menu_overall_marks -> {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/Student/overallMarks.aspx")
                return true
            }
            R.id.menu_semwise_marks -> {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/Student/OverallMarksSemwise.aspx")
                return true
            }
            R.id.menu_honor_minor -> {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/Student/OverallMarksHonorsMinors.aspx")
                return true
            }
            R.id.menu_internal -> {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/Student/StudentHallTicketDownloadingInternal.aspx")
                return true
            }
            R.id.menu_hall_ticket -> {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/Student/StudentHallTicketDownloading.aspx")
                return true
            }
            R.id.menu_script -> {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/Student/StudentScriptviewDownloading.aspx")
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
