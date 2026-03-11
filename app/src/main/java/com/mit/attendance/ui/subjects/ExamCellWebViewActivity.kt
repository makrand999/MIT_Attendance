package com.mit.attendance.ui.subjects

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.URI

class ExamCellWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErpWebviewBinding
    private lateinit var prefs: UserPreferences
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(CookieHandler.getDefault() ?: java.net.CookieManager().apply {
            setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            CookieHandler.setDefault(this)
        }))
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErpWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Exam Cell"

        prefs = UserPreferences(applicationContext)

        setupWebView()
        
        lifecycleScope.launch {
            val userId = prefs.getExamCellUserId()
            if (userId.isNullOrEmpty()) {
                startActivity(Intent(this@ExamCellWebViewActivity, ExamCellLoginActivity::class.java))
                finish()
                return@launch
            }
            
            binding.progressBar.visibility = View.VISIBLE
            val success = performBackgroundLogin(userId, userId)
            binding.progressBar.visibility = View.GONE
            
            if (success) {
                binding.webView.loadUrl("https://mitaexamcell.in/StudentLogin/MainStud.aspx")
            } else {
                Toast.makeText(this@ExamCellWebViewActivity, "Login failed. Please check credentials.", Toast.LENGTH_LONG).show()
                prefs.saveExamCellUserId("")
                startActivity(Intent(this@ExamCellWebViewActivity, ExamCellLoginActivity::class.java))
                finish()
            }
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
    }

    private suspend fun performBackgroundLogin(userId: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. GET initial page to get session cookie
            val req1 = Request.Builder()
                .url("https://mitaexamcell.in/StudentLogin/Student/overallMarks.aspx")
                .build()
            val res1 = client.newCall(req1).execute()
            var doc = Jsoup.parse(res1.body?.string() ?: "")

            // 2. POST to lnkLogins
            val form2 = FormBody.Builder()
                .add("__EVENTTARGET", "lnkLogins")
                .add("__VIEWSTATE", doc.select("#__VIEWSTATE").attr("value"))
                .add("__VIEWSTATEGENERATOR", doc.select("#__VIEWSTATEGENERATOR").attr("value"))
                .add("__EVENTVALIDATION", doc.select("#__EVENTVALIDATION").attr("value"))
                .build()
            val req2 = Request.Builder()
                .url("https://mitaexamcell.in/Login.aspx")
                .post(form2)
                .build()
            val res2 = client.newCall(req2).execute()
            doc = Jsoup.parse(res2.body?.string() ?: "")

            // 3. POST to lnkStudent
            val form3 = FormBody.Builder()
                .add("__EVENTTARGET", "lnkStudent")
                .add("__VIEWSTATE", doc.select("#__VIEWSTATE").attr("value"))
                .add("__VIEWSTATEGENERATOR", doc.select("#__VIEWSTATEGENERATOR").attr("value"))
                .add("__EVENTVALIDATION", doc.select("#__EVENTVALIDATION").attr("value"))
                .build()
            val req3 = Request.Builder()
                .url("https://mitaexamcell.in/Login.aspx")
                .post(form3)
                .build()
            val res3 = client.newCall(req3).execute()
            doc = Jsoup.parse(res3.body?.string() ?: "")

            // 4. Final Login POST
            val form4 = FormBody.Builder()
                .add("txtUserId", userId)
                .add("txtPwd", pass)
                .add("btnLogin", "Login")
                .add("__VIEWSTATE", doc.select("#__VIEWSTATE").attr("value"))
                .add("__VIEWSTATEGENERATOR", doc.select("#__VIEWSTATEGENERATOR").attr("value"))
                .add("__EVENTVALIDATION", doc.select("#__EVENTVALIDATION").attr("value"))
                .build()
            val req4 = Request.Builder()
                .url("https://mitaexamcell.in/Login.aspx")
                .post(form4)
                .build()
            val res4 = client.newCall(req4).execute()
            val finalHtml = res4.body?.string() ?: ""
            
            if (finalHtml.contains("Invalid") || finalHtml.contains("Login.aspx")) {
                 return@withContext false
            }

            // Sync cookies to WebView
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            
            val uri = URI.create("https://mitaexamcell.in")
            val store = (CookieHandler.getDefault() as java.net.CookieManager).cookieStore
            val cookies = store.get(uri)
            
            for (cookie in cookies) {
                val cookieStr = "${cookie.name}=${cookie.value}; domain=${cookie.domain ?: "mitaexamcell.in"}; path=${cookie.path ?: "/"}"
                cookieManager.setCookie("https://mitaexamcell.in", cookieStr)
            }
            
            withContext(Dispatchers.Main) {
                cookieManager.flush()
            }

            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
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

    private class JavaNetCookieJar(private val cookieHandler: CookieHandler) : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cookieMap = mutableMapOf<String, List<String>>()
            cookieMap["Set-Cookie"] = cookies.map { it.toString() }
            cookieHandler.put(url.toUri(), cookieMap)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieHeaders = cookieHandler.get(url.toUri(), emptyMap())
            val cookies = mutableListOf<Cookie>()
            for ((key, value) in cookieHeaders) {
                if (("Cookie".equals(key, ignoreCase = true) || "Cookie2".equals(key, ignoreCase = true)) && value.isNotEmpty()) {
                    for (header in value) {
                        cookies.addAll(decodeHeader(url, header))
                    }
                }
            }
            return cookies
        }
        
        private fun decodeHeader(url: HttpUrl, header: String): List<Cookie> {
            val result = mutableListOf<Cookie>()
            var pos = 0
            val limit = header.length
            while (pos < limit) {
                val pairEnd = header.indexOf(';', pos).let { if (it == -1) limit else it }
                val equalsSign = header.indexOf('=', pos).let { if (it == -1 || it > pairEnd) -1 else it }
                if (equalsSign != -1) {
                    val name = header.substring(pos, equalsSign).trim()
                    val value = header.substring(equalsSign + 1, pairEnd).trim()
                    try {
                        Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain(url.host)
                            .build()
                            .also { result.add(it) }
                    } catch (e: Exception) {}
                }
                pos = pairEnd + 1
            }
            return result
        }
    }
}
