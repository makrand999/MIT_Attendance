package com.mit.attendance.ui.certifications

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mit.attendance.model.AutomationScript
import com.mit.attendance.model.AutomationStep
import com.mit.attendance.ui.microsoft.FccCookieManager
import com.mit.attendance.ui.microsoft.MicrosoftCookieManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class CertificationAutomationEngine(private val context: Context) {
    private val TAG = "CertAutomationEngine"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val variables = mutableMapOf<String, String>()
    
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onStepUpdate(step: Int, total: Int, message: String)
        fun onComplete(success: Boolean, message: String)
    }

    suspend fun execute(scriptUrl: String, callback: Callback) = withContext(Dispatchers.IO) {
        try {
            initWebView()
            
            val scriptJson = fetchScript(scriptUrl) ?: throw Exception("Failed to download automation script")
            val script = gson.fromJson(scriptJson, AutomationScript::class.java)
            
            val total = script.steps.size
            script.steps.forEachIndexed { index, step ->
                callback.onStepUpdate(index + 1, total, "Executing: ${step.name}")
                
                // 1. Run Pre-Compute JS if exists
                step.preComputeJs?.let { js ->
                    val result = evaluateJs(js)
                    Log.d(TAG, "Pre-compute JS result: $result")
                    parseJsResult(result)
                }

                // 2. Execute Network Step
                val success = executeStep(step)
                if (!success) throw Exception("Step '${step.name}' failed")

                // 3. Run Post-Compute JS if exists
                step.postComputeJs?.let { js ->
                    val result = evaluateJs(js)
                    Log.d(TAG, "Post-compute JS result: $result")
                    parseJsResult(result)
                }
            }
            
            callback.onComplete(true, "Automation completed successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Automation failed", e)
            callback.onComplete(false, e.message ?: "Unknown error")
        } finally {
            destroyWebView()
        }
    }

    private suspend fun initWebView() = withContext(Dispatchers.Main) {
        if (webView == null) {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
            }
        }
    }

    private suspend fun destroyWebView() = withContext(Dispatchers.Main) {
        webView?.destroy()
        webView = null
    }

    private suspend fun evaluateJs(code: String): String = suspendCancellableCoroutine { cont ->
        val sb = StringBuilder()
        // Inject variables into JS context as global variables
        variables.forEach { (k, v) ->
            val escapedValue = v.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            sb.append("var $k = \"$escapedValue\";\n")
        }
        sb.append(code)
        val finalCode = sb.toString()
        
        mainHandler.post {
            webView?.evaluateJavascript(finalCode) { result ->
                // WebView returns results wrapped in quotes and escaped
                val cleanResult = if (result != null && result != "null") {
                    if (result.startsWith("\"") && result.endsWith("\"")) {
                        result.substring(1, result.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    } else {
                        result
                    }
                } else ""
                cont.resume(cleanResult)
            }
        }
    }

    private fun parseJsResult(result: String) {
        if (result.isBlank()) return
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(result, type)
            map.forEach { (k, v) ->
                variables[k] = v.toString()
            }
        } catch (e: Exception) {
            Log.d(TAG, "JS result was not a JSON map, ignoring: $result")
        }
    }

    private fun fetchScript(url: String): String? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { it.body?.string() }
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun executeStep(step: AutomationStep): Boolean {
        val processedUrl = replaceVariables(step.url)
        val requestBuilder = Request.Builder().url(processedUrl)

        step.headers?.forEach { (k, v) ->
            requestBuilder.header(k, replaceVariables(v))
        }

        if (processedUrl.contains("freecodecamp.org")) {
            val cookies = FccCookieManager.getCookieHeader(context)
            if (cookies.isNotEmpty()) requestBuilder.header("Cookie", cookies)
            val csrf = FccCookieManager.getCookie(context, "csrf_token")
            if (csrf != null) requestBuilder.header("Csrf-Token", csrf)
        } else if (processedUrl.contains("microsoft.com")) {
            val cookies = MicrosoftCookieManager.getCookieHeader(context)
            if (cookies.isNotEmpty()) requestBuilder.header("Cookie", cookies)
            val token = MicrosoftCookieManager.getDocsToken(context)
            if (token != null) requestBuilder.header("Authorization", "Bearer $token")
        }

        if (step.method.equals("POST", true)) {
            val bodyStr = step.body?.let { replaceVariables(it) } ?: "{}"
            requestBuilder.post(bodyStr.toRequestBody("application/json".toMediaType()))
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                
                if (response.code != step.expectedStatus && step.expectedStatus != -1) {
                    Log.e(TAG, "Step failed. Expected ${step.expectedStatus}, got ${response.code}. Body: $body")
                    return false
                }
                
                variables["lastResponse"] = body
                variables["lastStatus"] = response.code.toString()
                
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error in step", e)
            false
        }
    }

    private fun replaceVariables(input: String): String {
        var result = input
        variables.forEach { (k, v) ->
            result = result.replace("{{$k}}", v)
        }
        return result
    }
}
