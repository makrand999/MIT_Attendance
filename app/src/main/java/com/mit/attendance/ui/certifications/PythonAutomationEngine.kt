package com.mit.attendance.ui.certifications

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.Gson
import com.mit.attendance.BuildConfig
import com.mit.attendance.ui.subjects.GenericWebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class NetworkLog(
    val url: String,
    val method: String,
    val statusCode: Int,
    val responseBody: String?,
    val requestHeaders: Map<String, String>,
    val headers: Map<String, String>
)

object NetworkInterceptor {
    val logs = mutableListOf<NetworkLog>()
    var isEnabled = false

    fun addLog(log: NetworkLog) {
        if (isEnabled) {
            synchronized(logs) {
                logs.add(log)
            }
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}

class PythonAutomationEngine(private val context: Context) {
    private val TAG = "PythonAutomationEngine"
    private val client = OkHttpClient()
    private val githubToken = BuildConfig.GITHUB_TOKEN_MAIN
    private val gson = Gson()

    interface Callback {
        fun onStepUpdate(step: Int, total: Int, message: String)
        fun onComplete(success: Boolean, message: String)
    }

    inner class CookieProvider {
        fun getCookies(url: String): String? {
            return CookieManager.getInstance().getCookie(url)
        }

        fun openWebview(url: String) {
            val intent = Intent(context, GenericWebViewActivity::class.java).apply {
                putExtra("EXTRA_URL", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun getNetworkLogs(): Array<String> {
            return synchronized(NetworkInterceptor.logs) {
                NetworkInterceptor.logs.map { gson.toJson(it) }.toTypedArray()
            }
        }

        fun enableNetworkLogging(enabled: Boolean) {
            NetworkInterceptor.isEnabled = enabled
            // Always clear logs when toggling to ensure a fresh session for capture
            NetworkInterceptor.clear()
        }
    }

    suspend fun execute(scriptUrl: String?, localFile: File? = null, callback: Callback) = withContext(Dispatchers.IO) {
        try {
            ensurePythonStarted()
            
            val finalScriptFile = if (localFile != null && localFile.exists()) {
                localFile
            } else if (scriptUrl != null) {
                callback.onStepUpdate(0, 1, "Downloading script...")
                downloadScript(scriptUrl) ?: throw Exception("Failed to download script from $scriptUrl")
            } else {
                throw Exception("No script provided")
            }
            
            val py = Python.getInstance()
            val bridge = py.getModule("bridge")
            val mitAuto = py.getModule("mit_automation")
            
            mitAuto.put("_cookie_provider", CookieProvider())
            mitAuto.put("_callback", callback)

            val result = bridge.callAttr(
                "run_automation_script_v2",
                finalScriptFile.absolutePath
            ).toString()

            if (result == "ok") {
                callback.onComplete(true, "Automation finished successfully!")
            } else {
                callback.onComplete(false, "Python Error: $result")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            callback.onComplete(false, e.message ?: "Unknown error")
        }
    }

    private fun ensurePythonStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private fun downloadScript(url: String): File? {
        val rawUrl = if (url.contains("github.com") && url.contains("/blob/")) {
            url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
        } else url

        val request = Request.Builder()
            .url(rawUrl)
            .header("Authorization", "token $githubToken")
            .build()
            
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            
            val cacheDir = File(context.filesDir, "automation_scripts")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val fileName = url.substringAfterLast("/")
            val scriptFile = File(cacheDir, if (fileName.endsWith(".py")) fileName else "script.py")
            FileOutputStream(scriptFile).use { output ->
                response.body?.byteStream()?.copyTo(output)
            }
            scriptFile
        } catch (e: IOException) {
            null
        }
    }
}
