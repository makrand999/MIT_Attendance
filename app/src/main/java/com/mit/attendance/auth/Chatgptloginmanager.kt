package com.mit.attendance.auth
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG          = "ChatGptLoginManager"
private const val CHATGPT_HOME = "https://chatgpt.com"
private const val LOGIN_URL    = "https://chatgpt.com/auth/login"
private const val PREFS_FILE   = "chaipatti_auth"
private const val KEY_COOKIES  = "saved_cookies"
private const val KEY_LOGGED_IN = "is_logged_in"

// ─────────────────────────────────────────
//  CHATGPT LOGIN MANAGER
//
//  Manages the full login lifecycle:
//    1. Show a visible WebView → user logs in manually
//    2. Detect when login succeeds by watching the URL
//    3. Extract & save cookies to EncryptedSharedPreferences
//    4. On future launches, restore cookies directly into
//       the hidden agent WebView — no login needed again
//    5. Detect cookie expiry and trigger re-login
//
//  Usage:
//    val login = ChatGptLoginManager(context)
//
//    if (login.isLoggedIn()) {
//        // restore cookies directly into agent WebView
//        login.restoreCookiesInto(agentWebView)
//        startAgent()
//    } else {
//        // show login screen
//        login.startLoginFlow(loginWebView,
//            onSuccess = { startAgent() },
//            onError   = { showError(it) }
//        )
//    }
// ─────────────────────────────────────────
class ChatGptLoginManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── ENCRYPTED PREFS ───────────────────────────────────────────
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── PUBLIC API ────────────────────────────────────────────────

    /** True if we have saved cookies from a previous login. */
    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    /**
     * Restores saved cookies into [webView]'s CookieManager.
     * Call this before loading ChatGPT in the agent WebView.
     * Returns false if no saved cookies exist.
     */
    fun restoreCookiesInto(webView: WebView): Boolean {
        val saved = prefs.getString(KEY_COOKIES, null) ?: return false
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        saved.split("|||").forEach { raw ->
            if (raw.isNotBlank()) {
                cm.setCookie(CHATGPT_HOME, raw.trim())
            }
        }
        cm.flush()
        Log.d(TAG, "✅ Cookies restored from storage")
        return true
    }

    /**
     * Sets up [loginWebView] to show the ChatGPT login page.
     * Watches for successful login and saves cookies automatically.
     *
     * [onSuccess] — called on main thread when login is detected
     * [onError]   — called if something goes wrong
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun startLoginFlow(
        loginWebView: WebView,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        loginWebView.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            // Use a real desktop Chrome UA — ChatGPT's OAuth flow
            // works more reliably with a desktop agent
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Mobile Safari/537.36"
            setSupportZoom(true)
            loadWithOverviewMode     = true
            useWideViewPort          = true
            javaScriptCanOpenWindowsAutomatically = true
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(loginWebView, true)

        loginWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "Page: $url")

                // ── Detect successful login ────────────────────
                // After login ChatGPT redirects to chatgpt.com or chatgpt.com/?...
                // We know we're logged in when we land on the home page
                // AND the page has the prompt composer element
                if (isLoggedInUrl(url)) {
                    checkComposerAndFinish(view, url, onSuccess, onError)
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.e(TAG, "Login page error: ${error.description}")
                    // Don't call onError here — intermediate redirects
                    // during OAuth often fire errors that resolve themselves
                }
            }
        }

        // Chrome custom tabs / popups during Google OAuth need this
        loginWebView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                // Allow OAuth popup windows by routing them back into the same WebView
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }

        loginWebView.loadUrl(LOGIN_URL)
        Log.d(TAG, "🌐 Login page loaded")
    }

    /**
     * Saves the current cookies from [webView] back to encrypted storage.
     * Call this periodically or on app pause to keep cookies fresh.
     */
    fun saveCookiesFrom(webView: WebView) {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie(CHATGPT_HOME) ?: return
        // CookieManager returns a single string "name=val; name2=val2"
        // We split on "; " and store as "|||" delimited for easy restore
        val split = raw.split("; ").joinToString("|||")
        prefs.edit()
            .putString(KEY_COOKIES, split)
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
        Log.d(TAG, "💾 Cookies saved (${raw.length} chars)")
    }

    /** Clears saved login state — forces re-login next launch. */
    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        prefs.edit()
            .remove(KEY_COOKIES)
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()
        Log.d(TAG, "🚪 Logged out — cookies cleared")
    }

    /**
     * Checks if cookies are still valid by verifying the agent WebView
     * lands on the ChatGPT home with the composer visible.
     * Call after restoring cookies, before starting the agent.
     */
    fun verifyCookies(
        webView: WebView,
        onValid: () -> Unit,
        onExpired: () -> Unit
    ) {
        webView.loadUrl(CHATGPT_HOME)
        val deadline = System.currentTimeMillis() + 20_000L

        fun poll() {
            if (System.currentTimeMillis() > deadline) { onExpired(); return }
            webView.evaluateJavascript(
                "document.getElementById('prompt-textarea') !== null ? 'yes' : 'no'"
            ) { result ->
                when {
                    result?.contains("yes") == true -> {
                        Log.d(TAG, "✅ Cookies valid")
                        onValid()
                    }
                    // Check if we got redirected to login — means cookies expired
                    else -> webView.evaluateJavascript("window.location.href") { href ->
                        if (href?.contains("auth/login") == true) {
                            Log.w(TAG, "⚠️ Cookies expired — redirected to login")
                            logout()
                            onExpired()
                        } else {
                            mainHandler.postDelayed(::poll, 600)
                        }
                    }
                }
            }
        }
        mainHandler.postDelayed(::poll, 1500)
    }

    // ── INTERNAL ──────────────────────────────────────────────────

    private fun isLoggedInUrl(url: String): Boolean {
        return url.startsWith("https://chatgpt.com") &&
                !url.contains("auth/login") &&
                !url.contains("auth/signup") &&
                !url.contains("accounts.google") &&
                !url.contains("login.microsoftonline")
    }

    private fun checkComposerAndFinish(
        view: WebView,
        url: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val deadline = System.currentTimeMillis() + 15_000L

        fun poll() {
            if (System.currentTimeMillis() > deadline) {
                onError("Login timed out — composer not found")
                return
            }
            view.evaluateJavascript(
                "document.getElementById('prompt-textarea') !== null ? 'yes' : 'no'"
            ) { result ->
                if (result?.contains("yes") == true) {
                    // Logged in! Save cookies and notify caller
                    saveCookiesFrom(view)
                    Log.d(TAG, "✅ Login successful at $url")
                    mainHandler.post { onSuccess() }
                } else {
                    mainHandler.postDelayed(::poll, 500)
                }
            }
        }
        poll()
    }
}