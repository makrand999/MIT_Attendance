package com.mit.attendance.ui.microsoft

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FccCookie(
    val name: String,
    val value: String,
    val domain: String
)

object FccCookieManager {
    private const val TAG = "FccCookieManager"
    private const val COOKIE_FILE_NAME = "freecodecamp_cookies.json"
    private const val TARGET_DOMAIN = ".freecodecamp.org"
    private const val TARGET_URL = "https://www.freecodecamp.org"
    private const val API_URL = "https://api.freecodecamp.org"

    private val gson = Gson()

    @Volatile
    private var cookieCache: MutableMap<String, FccCookie>? = null

    suspend fun injectCookies(context: Context) {
        val cookies = getCachedCookies(context)
        if (cookies.isEmpty()) return

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        withContext(Dispatchers.Main) {
            cookies.forEach { cookie ->
                val cookieString = "${cookie.name}=${cookie.value}; domain=${cookie.domain}; path=/"
                cookieManager.setCookie(TARGET_URL, cookieString)
                cookieManager.setCookie(API_URL, cookieString)
            }
            cookieManager.flush()
        }
        Log.d(TAG, "Injected ${cookies.size} FCC cookies")
    }

    suspend fun syncCookies(context: Context) {
        val cookieManager = CookieManager.getInstance()
        
        val urls = listOf(TARGET_URL, API_URL, "https://freecodecamp.org")
        val cached = getCachedCookies(context).associateBy { it.name }.toMutableMap()
        var changed = false

        withContext(Dispatchers.Main) {
            urls.forEach { url ->
                val header = cookieManager.getCookie(url)
                if (header != null) {
                    val currentCookies = parseCookieHeader(header)
                    currentCookies.forEach { (name, value) ->
                        val existing = cached[name]
                        if (existing == null || existing.value != value) {
                            cached[name] = FccCookie(name, value, TARGET_DOMAIN)
                            changed = true
                            Log.d(TAG, "Sync: Found/Updated cookie $name")
                        }
                    }
                }
            }
        }

        if (changed) {
            synchronized(this) {
                cookieCache = cached
            }
            persistCookies(context)
            Log.d(TAG, "FCC Cookies updated and persisted. Total: ${cached.size}")
        }
    }

    suspend fun persistCookies(context: Context) = withContext(Dispatchers.IO) {
        val cookies = synchronized(this@FccCookieManager) {
            cookieCache?.values?.toList()
        } ?: return@withContext
        try {
            val file = File(context.filesDir, COOKIE_FILE_NAME)
            val json = gson.toJson(cookies)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting FCC cookies", e)
        }
    }

    fun getCookie(context: Context, name: String): String? {
        val cookies = getCachedCookiesSync(context)
        return cookies.find { it.name.equals(name, ignoreCase = true) }?.value
    }

    fun getCookieHeader(context: Context): String {
        val cookies = getCachedCookiesSync(context)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    private fun getCachedCookiesSync(context: Context): List<FccCookie> {
        cookieCache?.let { return it.values.toList() }
        
        val stored = try {
            val file = File(context.filesDir, COOKIE_FILE_NAME)
            if (!file.exists()) emptyList()
            else {
                val json = file.readText()
                val type = object : TypeToken<List<FccCookie>>() {}.type
                gson.fromJson<List<FccCookie>>(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        val map = stored.associateBy { it.name }.toMutableMap()
        cookieCache = map
        return stored
    }

    private suspend fun getCachedCookies(context: Context): List<FccCookie> = withContext(Dispatchers.IO) {
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
