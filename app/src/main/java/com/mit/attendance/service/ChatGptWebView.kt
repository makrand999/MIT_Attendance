package com.mit.attendance.service

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume

private const val TAG = "ChatGptWebView"
private const val CHATGPT_URL = "https://chatgpt.com/?temporary-chat=true"
private const val RESPONSE_TIMEOUT_MS = 60_000L

/**
 * CHAIPATTI — WebView + JS core
 * Optimized for background agent performance.
 */
class ChatGptWebView(private val context: Context) {

    val webView: WebView = WebView(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isReady = false
        private set

    @Volatile
    var isPaused = false

    private val onReadyListeners = mutableListOf<() -> Unit>()

    private fun notifyReady() {
        mainHandler.post {
            if (isReady) return@post
            isReady = true
            Log.d(TAG, "🔔 notifyReady: ChatGPT is interactive")
            val listeners = onReadyListeners.toList()
            onReadyListeners.clear()
            listeners.forEach { it() }
        }
    }

    suspend fun awaitReady() = suspendCancellableCoroutine<Unit> { cont ->
        mainHandler.post {
            if (isReady) {
                cont.resume(Unit)
            } else {
                onReadyListeners.add {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun init(
        cookies: String? = null,
        loginManager: com.mit.attendance.auth.ChatGptLoginManager? = null,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val initStartTime = System.currentTimeMillis()
        Log.d(TAG, "🚀 init: Starting WebView initialization...")

        mainHandler.post {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36"
                setSupportZoom(false)
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            onReady?.let { onReadyListeners.add(it) }

            // Restore cookies if available
            loginManager?.restoreCookiesInto(webView) ?: injectCookies(cookies ?: "")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "🌐 onPageFinished: $url")
                    if (!url.contains("chatgpt.com")) return

                    if (url.contains("auth/login")) {
                        Log.e(TAG, "❌ onPageFinished: Session expired detected")
                        onError?.invoke("Session expired")
                        return
                    }
                    
                    val waitStartTime = System.currentTimeMillis()
                    Log.d(TAG, "⏱️ onPageFinished: Waiting for ChatGPT composer...")
                    // Wait for React hydration
                    waitForComposer(20_000L, {
                        Log.d(TAG, "✅ onPageFinished: Composer found in ${System.currentTimeMillis() - waitStartTime}ms")
                        mainHandler.postDelayed({ 
                            notifyReady() 
                            Log.d(TAG, "🏁 init: Full initialization complete in ${System.currentTimeMillis() - initStartTime}ms")
                        }, 1000)
                    }, { 
                        Log.e(TAG, "❌ onPageFinished: Composer wait timeout")
                        onError?.invoke("Timeout") 
                    })
                }
            }
            webView.loadUrl(CHATGPT_URL)
        }
    }

    suspend fun send(message: String): String = withContext(Dispatchers.Main) {
        val sendStartTime = System.currentTimeMillis()
        
        while (isPaused) {
            delay(1000)
        }

        Log.d(TAG, "✉️ send: Preparing to send message (length: ${message.length})...")
        
        if (!isReady) {
            Log.d(TAG, "⏱️ send: WebView not ready, awaiting...")
            awaitReady()
            Log.d(TAG, "✅ send: WebView ready after wait")
        }

        val existingCount = evalJs("document.querySelectorAll('[data-message-author-role=\"assistant\"]').length").toIntOrNull() ?: 0
        Log.d(TAG, "📊 send: Existing assistant messages: $existingCount")

        // 1. INPUT TEXT
        val inputStartTime = System.currentTimeMillis()
        val inputResult = evalJs("""
            (function() {
                const el = document.getElementById('prompt-textarea');
                if (!el) return 'no_composer';

                el.focus();
                // Clear existing
                document.execCommand('selectAll', false, null);
                document.execCommand('delete', false, null);
                
                // Insert new text
                document.execCommand('insertText', false, ${jsonString(message)});

                // Dispatch input event for React state sync
                el.dispatchEvent(new InputEvent('input', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: ${jsonString(message)}
                }));
                return 'ok';
            })()
        """.trimIndent())
        
        if (inputResult != "ok") {
            Log.e(TAG, "❌ send: Input failed: $inputResult")
            error("Failed to input message")
        }
        Log.d(TAG, "✅ send: Input injected in ${System.currentTimeMillis() - inputStartTime}ms")

        // Wait for React to process the input and enable the send button
        delay(600)

        // 2. CLICK SEND
        val clickStartTime = System.currentTimeMillis()
        val clickResult = evalJs("""
            (function() {
                const btn = document.querySelector('button[data-testid="send-button"]');
                if (btn && !btn.disabled) {
                    btn.click();
                    return 'clicked';
                }
                
                // Fallback: Enter key
                const el = document.getElementById('prompt-textarea');
                if (el) {
                    el.dispatchEvent(new KeyboardEvent('keydown', {
                        key: 'Enter', code: 'Enter', keyCode: 13,
                        bubbles: true, cancelable: true
                    }));
                    return 'enter';
                }
                return 'no_action_possible';
            })()
        """.trimIndent())
        
        Log.d(TAG, "🚀 send: Click action '$clickResult' performed in ${System.currentTimeMillis() - clickStartTime}ms")

        // 3. WAIT FOR REPLY
        val waitReplyStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ send: Waiting for assistant to start replying...")
        val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
        var appeared = false
        while (System.currentTimeMillis() < deadline) {
            val count = evalJs("document.querySelectorAll('[data-message-author-role=\"assistant\"]').length").toIntOrNull() ?: 0
            if (count > existingCount) { appeared = true; break }
            delay(1000)
        }
        if (!appeared) {
            Log.e(TAG, "❌ send: Timeout waiting for reply")
            error("ChatGPT did not reply in time")
        }
        Log.d(TAG, "✅ send: Assistant started replying after ${System.currentTimeMillis() - waitReplyStartTime}ms")

        // 4. WAIT FOR STREAMING FINISH
        val streamingStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ send: Waiting for streaming to finish...")
        val streamDeadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < streamDeadline) {
            val isStreaming = evalJs("document.querySelector('button[aria-label=\"Stop streaming\"]') !== null ? 'yes' : 'no'")
            if (isStreaming == "no") break
            delay(1000)
        }
        Log.d(TAG, "✅ send: Streaming finished in ${System.currentTimeMillis() - streamingStartTime}ms")

        delay(800)
        val extractStartTime = System.currentTimeMillis()
        val reply = evalJs("""
            (function() {
                var msgs = document.querySelectorAll('[data-message-author-role=\"assistant\"]');
                if (!msgs.length) return '';
                var last = msgs[msgs.length - 1];
                var md = last.querySelector('.markdown, [class*=\"prose\"]');
                return (md || last).innerText || '';
            })()
        """.trimIndent())

        Log.d(TAG, "✅ send: Reply extracted in ${System.currentTimeMillis() - extractStartTime}ms. Total send time: ${System.currentTimeMillis() - sendStartTime}ms")
        reply.trim()
    }

    fun refresh(onReady: (() -> Unit)? = null) {
        mainHandler.post {
            Log.d(TAG, "🔄 refresh: Refreshing ChatGPT page...")
            isReady = false
            onReady?.let { onReadyListeners.add(it) }
            webView.loadUrl(CHATGPT_URL)
        }
    }

    fun clearCookies() {
        mainHandler.post {
            Log.d(TAG, "🗑️ clearCookies: Clearing all cookies and site data")
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    fun injectCookies(cookieString: String, domain: String = ".chatgpt.com") {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cookieString.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { pair ->
            cm.setCookie(domain, pair)
        }
        cm.flush()
    }

    fun destroy() {
        mainHandler.post {
            Log.d(TAG, "🗑️ destroy: Cleaning up WebView")
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun evalJs(script: String): String = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            webView.evaluateJavascript(script) { result ->
                val clean = if (result == "null" || result == null) ""
                else result.removeSurrounding("\"")
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                if (cont.isActive) cont.resume(clean)
            }
        }
    }

    private fun waitForComposer(timeoutMs: Long, onFound: () -> Unit, onTimeout: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun poll() {
            if (System.currentTimeMillis() > deadline) { onTimeout(); return }
            webView.evaluateJavascript("document.getElementById('prompt-textarea') !== null ? 'yes' : 'no'") { result ->
                if (result?.contains("yes") == true) onFound()
                else mainHandler.postDelayed(::poll, 1000)
            }
        }
        poll()
    }

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""
}
