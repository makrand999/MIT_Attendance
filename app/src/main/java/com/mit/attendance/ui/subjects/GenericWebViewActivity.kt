package com.mit.attendance.ui.subjects

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.mit.attendance.databinding.ActivityErpWebviewBinding
import com.mit.attendance.ui.certifications.NetworkInterceptor
import com.mit.attendance.ui.certifications.NetworkLog
import okhttp3.OkHttpClient
import okhttp3.Request

class GenericWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErpWebviewBinding
    private val okClient = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErpWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("EXTRA_URL") ?: "https://www.google.com"
        val isFromNotes = intent.getBooleanExtra("FROM_NOTES", false)

        if (isFromNotes) {
            WebViewWindowManager.addWebView(binding.root.findViewById(com.mit.attendance.R.id.webViewContainer) ?: return, binding.root)
            finish()
            return
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
        
        binding.toolbar.visibility = View.GONE
        binding.btnBack.visibility = View.VISIBLE

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
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

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || !NetworkInterceptor.isEnabled) return null

                val urlStr = request.url.toString()
                
                // For logging purposes, we want to capture headers even if we don't intercept
                if (urlStr.contains("api") || urlStr.contains("json") || urlStr.contains("token") || urlStr.contains("auth")) {
                    // Strategy B: Capture request headers regardless of method
                    if (request.method != "GET") {
                        NetworkInterceptor.addLog(NetworkLog(
                            url = urlStr,
                            method = request.method,
                            statusCode = 0,
                            responseBody = null,
                            requestHeaders = request.requestHeaders,
                            headers = emptyMap()
                        ))
                    }
                }

                // We can only reliably intercept and re-execute GET requests to get the response body
                if (request.method != "GET" || request.isForMainFrame) return null

                // Skip if it explicitly asks for HTML (navigation or subframe)
                val accept = request.requestHeaders["Accept"] ?: ""
                if (accept.contains("text/html")) return null

                // Filter for API-like requests
                if (urlStr.contains("api") || urlStr.contains("json") || urlStr.contains("token") || urlStr.contains("auth")) {
                    try {
                        val builder = Request.Builder().url(urlStr)
                        
                        // Pass through WebView headers (except Host and Cookie which we handle)
                        request.requestHeaders.forEach { (k, v) -> 
                            if (!k.equals("Host", ignoreCase = true) && !k.equals("Cookie", ignoreCase = true)) {
                                builder.header(k, v)
                            }
                        }
                        
                        // Sync cookies from CookieManager
                        val cookies = CookieManager.getInstance().getCookie(urlStr)
                        if (cookies != null) {
                            builder.header("Cookie", cookies)
                        }
                        
                        val response = okClient.newCall(builder.build()).execute()
                        val body = response.body ?: return null
                        
                        val contentTypeHeader = response.header("Content-Type")
                        
                        // Final check: if server returned HTML, don't intercept (let WebView render it)
                        if (contentTypeHeader?.contains("text/html", ignoreCase = true) == true) {
                            response.close()
                            return null
                        }

                        val bytes = body.bytes()
                        val responseBodyString = String(bytes)
                        val mimeType = contentTypeHeader?.split(";")?.get(0)?.trim() ?: "application/json"

                        NetworkInterceptor.addLog(NetworkLog(
                            url = urlStr,
                            method = request.method,
                            statusCode = response.code,
                            responseBody = responseBodyString,
                            requestHeaders = request.requestHeaders,
                            headers = response.headers.toMap()
                        ))

                        return WebResourceResponse(
                            mimeType,
                            "UTF-8",
                            response.code,
                            "OK",
                            response.headers.toMap(),
                            bytes.inputStream()
                        )
                    } catch (e: Exception) {
                        Log.e("WebViewIntercept", "Error intercepting $urlStr", e)
                    }
                }
                
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.webView.loadUrl(url)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
