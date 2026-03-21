package com.mit.attendance.ui.practical

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class ChatGPTWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_FIELD_TYPE = "extra_field_type"
        const val EXTRA_PASTED_TEXT = "extra_pasted_text"
        const val FIELD_THEORY = "theory"
        const val FIELD_CONCLUSION = "conclusion"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var fieldType: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fieldType = intent.getStringExtra(EXTRA_FIELD_TYPE)
        
        val root = android.widget.FrameLayout(this)
        webView = WebView(this)
        webView.visibility = View.INVISIBLE
        
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.isIndeterminate = true
        progressBar.visibility = View.GONE
        
        root.addView(webView)
        root.addView(progressBar, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        setContentView(root)

        supportActionBar?.apply {
            title = "ChatGPT AI"
            setDisplayHomeAsUpEnabled(true)
        }

        setupWebView()
        webView.loadUrl("https://chatgpt.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Modern User Agent to avoid legacy mobile site issues
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("chatgpt.com") == true) {
                    // CRITICAL: If on a login or captcha page, show it and don't inject the "hider" script
                    if (url.contains("/auth") || url.contains("/login") || url.contains("/signup") || url.contains("challenges.cloudflare.com")) {
                        webView.visibility = View.VISIBLE
                        progressBar.visibility = View.GONE
                        return
                    }
                    
                    val prompt = intent.getStringExtra(EXTRA_PROMPT)
                    injectMunishScript(prompt)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onCopyResponse() {
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                val text = if (clipData != null && clipData.itemCount > 0) {
                    clipData.getItemAt(0).text?.toString()
                } else null

                val resultIntent = Intent().apply {
                    putExtra(EXTRA_FIELD_TYPE, fieldType)
                    if (text != null) {
                        putExtra(EXTRA_PASTED_TEXT, text)
                    }
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    private fun injectMunishScript(prompt: String? = null) {
        val escapedPrompt = prompt?.replace("\\", "\\\\")
            ?.replace("\"", "\\\"")
            ?.replace("\n", "\\n")
            ?.replace("\r", "") ?: ""

        val promptJs = if (prompt != null) {
            """
            (function() {
                function tryInject() {
                    const el = document.querySelector('textarea[name="prompt-textarea"]') || document.querySelector('#prompt-textarea');
                    if (el && !el.disabled) {
                        el.focus();
                        document.execCommand("selectAll", false, null);
                        document.execCommand("delete", false, null);
                        document.execCommand("insertText", false, "$escapedPrompt");
                        
                        // Sync React state
                        el.dispatchEvent(new InputEvent('input', { 
                            bubbles: true, 
                            cancelable: true, 
                            inputType: 'insertText', 
                            data: "$escapedPrompt" 
                        }));
                        return true;
                    }
                    return false;
                }
                
                if (!tryInject()) {
                    const observer = new MutationObserver((mutations, obs) => {
                        if (tryInject()) obs.disconnect();
                    });
                    observer.observe(document.body, { childList: true, subtree: true });
                }
            })();
            """.trimIndent()
        } else ""

        val munishJs = """
            /**
             * MUNISH - Optimized Injection
             * Throttled observer + CSS-heavy hiding to prevent UI freezes.
             */
            (function () {
              'use strict';
              if (window.__MUNISH__) return;
              window.__MUNISH__ = true;

              /* ── 1. CSS HIDING (Most efficient way to hide elements) ── */
              const style = document.createElement('style');
              style.textContent = `
                /* Hide sidebar, headers, and unwanted UI chrome */
                nav, [class*="sidebar"], [data-testid="sidebar"], [data-testid="navigation"],
                header, [id="page-header"], [class*="page-header"], .draggable.sticky.top-0,
                div.flex.grow.flex-col.text-center, svg[aria-label="ChatGPT logo"],
                [class*="scroll-to-bottom"], button[class*="absolute"][class*="rounded-full"][class*="z-30"],
                [class*="disclaimer"], [class*="text-token-text-secondary"][class*="text-center"][class*="text-xs"],
                button[aria-label="Dictate button"], button[aria-label="Start Voice"],
                [class*="bottom-full"] aside, aside:has([data-testid="close-button"]),
                
                /* Popups and Radix Portals */
                [data-radix-portal], [data-radix-dialog-overlay], [data-radix-dialog-content],
                [data-radix-popper-content-wrapper], [role="dialog"], [role="alertdialog"],
                [aria-modal="true"], [data-testid="login-wall"], [data-testid="cookie-consent"],
                [data-testid*="modal"], [data-testid*="dialog"], [data-testid*="banner"],
                [data-testid*="tooltip"], [data-testid*="popover"], [data-testid="upsell-modal"],
                [data-testid="rate-limit-modal"], [data-testid="interstitial"],
                
                /* Generic overlays */
                [class*="Modal"]:not(#munish-shell), [class*="modal"]:not(#munish-shell),
                [class*="Dialog"]:not(#munish-shell), [class*="dialog"]:not(#munish-shell),
                [class*="Overlay"]:not(#munish-shell), [class*="overlay"]:not(#munish-shell),
                [class*="Backdrop"], [class*="backdrop"], [class*="Popover"], [class*="popover"],
                [class*="Tooltip"], [class*="tooltip"], [class*="Banner"], [class*="banner"],
                [class*="cookie"], [class*="consent"], [class*="paywall"], [class*="upsell"],
                [class*="upgrade"], .tippy-box, .tippy-popper, .ReactModalPortal {
                  display: none !important;
                  pointer-events: none !important;
                }

                /* Layout adjustments */
                [class*="@container/main"] { width: 100% !important; max-width: 100% !important; }
                
                .chaipatti-logo {
                  font-family: 'DM Sans', sans-serif;
                  font-size: 22px;
                  font-weight: 700;
                  letter-spacing: 0.04em;
                  color: #e4e4e7;
                }
                .chaipatti-logo span { color: #a78bfa; }
              `;
              document.head.appendChild(style);

              /* ── 2. JS SWEEPER (Throttled for performance) ── */
              const KILL_TEXT = [
                /log\s*in/i, /sign\s*in/i, /sign\s*up/i, /create.*account/i,
                /try.*plus/i, /upgrade/i, /rate.*limit/i, /cookie/i, /open.*app/i,
              ];

              function sweep() {
                // Kill text-pattern based dialogs
                document.querySelectorAll('[role="dialog"],[aria-modal="true"]').forEach(el => {
                  if (KILL_TEXT.some(p => p.test(el.innerText || ''))) {
                    el.style.setProperty('display','none','important');
                  }
                });

                // Replace Logo
                const svg = document.querySelector('svg[aria-label="ChatGPT logo"]');
                if (svg && !svg.parentNode.querySelector('.chaipatti-logo')) {
                    const logo = document.createElement('span');
                    logo.className = 'chaipatti-logo';
                    logo.innerHTML = 'CHAI<span>PATTI</span>';
                    svg.parentNode.insertBefore(logo, svg);
                }
              }

              function killConsentBanner() {
                const closeBtn = document.querySelector('aside [data-testid="close-button"], [data-testid="close-button"]');
                if (closeBtn) { closeBtn.click(); }
              }

              // Use a throttled MutationObserver (300ms) to avoid CPU spikes
              let throttleTimer = null;
              new MutationObserver(() => {
                if (throttleTimer) return;
                throttleTimer = setTimeout(() => {
                  sweep();
                  killConsentBanner();
                  throttleTimer = null;
                }, 300);
              }).observe(document.documentElement, { childList: true, subtree: true });

              // Less frequent intervals
              setInterval(() => { sweep(); killConsentBanner(); }, 3000);

              window.open    = () => null;
              window.alert   = () => {};
              window.confirm = () => false;
              window.prompt  = () => null;

              /* ── 3. Listen for Copy Button ── */
              document.addEventListener('click', function(e) {
                  const btn = e.target.closest('button[aria-label="Copy response"]');
                  if (btn) {
                      setTimeout(() => {
                          if (window.Android) window.Android.onCopyResponse();
                      }, 100);
                  }
              }, true);

              $promptJs

            })();
        """.trimIndent()
        
        webView.evaluateJavascript(munishJs) {
            webView.postDelayed({
                webView.visibility = View.VISIBLE
            }, 100)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
