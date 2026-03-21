package com.mit.attendance.ui.microsoft

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MicrosoftCookie(
    val name: String,
    val value: String,
    val domain: String
)

object MicrosoftCookieManager {
    private const val TAG = "MicrosoftCookieManager"
    private const val COOKIE_FILE_NAME = "microsoft_cookies.json"
    private const val TARGET_DOMAIN = "learn.microsoft.com"
    private const val TARGET_URL = "https://learn.microsoft.com"

    private val trackedCookies = setOf("DocsToken", "ai_session", "MS0", "MUID", "MC1", "MSFPC", "mbox")
    private val gson = Gson()

    private var cookieCache: MutableMap<String, MicrosoftCookie>? = null

    suspend fun injectCookies(context: Context) {
        val cookies = getCachedCookies(context)
        if (cookies.isEmpty()) return

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        withContext(Dispatchers.Main) {
            cookies.forEach { cookie ->
                val cookieString = "${cookie.name}=${cookie.value}; domain=${cookie.domain}; path=/"
                cookieManager.setCookie(TARGET_URL, cookieString)
            }
            cookieManager.flush()
        }
        Log.d(TAG, "Injected ${cookies.size} cookies")
    }

    /**
     * Updates in-memory cache from WebView. Does NOT write to disk.
     */
    suspend fun syncCookies(context: Context) {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = withContext(Dispatchers.Main) { 
            cookieManager.getCookie(TARGET_URL) 
        } ?: return
        
        val currentCookies = parseCookieHeader(cookieHeader)
        val cached = getCachedCookies(context).associateBy { it.name }.toMutableMap()

        var changed = false
        currentCookies.forEach { (name, value) ->
            if (trackedCookies.contains(name)) {
                val existing = cached[name]
                if (existing == null || existing.value != value) {
                    cached[name] = MicrosoftCookie(name, value, TARGET_DOMAIN)
                    changed = true
                }
            }
        }

        if (changed) {
            cookieCache = cached
            Log.d(TAG, "Cookies updated in cache")
        }
    }

    /**
     * Persists the current cache to disk. Call this when activity stops or job finishes.
     */
    suspend fun persistCookies(context: Context) = withContext(Dispatchers.IO) {
        val cookies = cookieCache?.values?.toList() ?: return@withContext
        try {
            val file = File(context.filesDir, COOKIE_FILE_NAME)
            val json = gson.toJson(cookies)
            file.writeText(json)
            Log.d(TAG, "Persisted ${cookies.size} cookies to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting cookies", e)
        }
    }

    fun isDocsTokenPresent(context: Context): Boolean {
        return getDocsToken(context) != null
    }

    fun getDocsToken(context: Context): String? {
        val cookies = getCachedCookiesSync(context)
        return cookies.find { it.name == "DocsToken" }?.value?.takeIf { it.isNotEmpty() }
    }

    fun getCookieHeader(context: Context): String {
        val cookies = getCachedCookiesSync(context)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun getCachedCookiesSync(context: Context): List<MicrosoftCookie> {
        cookieCache?.let { return it.values.toList() }
        
        val stored = try {
            val file = File(context.filesDir, COOKIE_FILE_NAME)
            if (!file.exists()) emptyList()
            else {
                val json = file.readText()
                val type = object : TypeToken<List<MicrosoftCookie>>() {}.type
                gson.fromJson<List<MicrosoftCookie>>(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        cookieCache = stored.associateBy { it.name }.toMutableMap()
        return stored
    }

    private suspend fun getCachedCookies(context: Context): List<MicrosoftCookie> = withContext(Dispatchers.IO) {
        getCachedCookiesSync(context)
    }

    private fun parseCookieHeader(header: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = header.split(";")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim()] = parts[1].trim()
            }
        }
        return result
    }
}
