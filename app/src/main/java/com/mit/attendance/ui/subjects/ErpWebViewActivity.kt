package com.mit.attendance.ui.subjects

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.mit.attendance.data.api.HttpClientHolder
import com.mit.attendance.databinding.ActivityErpWebviewBinding

class ErpWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErpWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErpWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Hide toolbar and show floating back button
        binding.toolbar.visibility = View.GONE
        binding.btnBack.visibility = View.VISIBLE

        val url = "https://erp.mit.asia/studentCourseFileNew.htm?shwA=%2700A%27"
        val sessionId = HttpClientHolder.getSessionId()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        if (sessionId != null) {
            // Set JSESSIONID cookie for the ERP domain
            val cookieString = "JSESSIONID=$sessionId; Domain=erp.mit.asia; Path=/; Secure; HttpOnly"
            cookieManager.setCookie("https://erp.mit.asia", cookieString)
        }
        cookieManager.flush()

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
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
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        binding.webView.loadUrl(url)
    }
}