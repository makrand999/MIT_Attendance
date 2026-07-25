package com.mit.attendance.ui.practical

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.mit.attendance.databinding.ActivityErpWebviewBinding

class ChatGPTWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_FIELD_TYPE = "extra_field_type"
        const val EXTRA_PASTED_TEXT = "extra_pasted_text"
        const val FIELD_THEORY = "theory"
        const val FIELD_CONCLUSION = "conclusion"
    }

    private lateinit var binding: ActivityErpWebviewBinding
    private var fieldType: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErpWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        fieldType = intent.getStringExtra(EXTRA_FIELD_TYPE)
        
        binding.webView.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.GONE
        
        // Setup bottom-left back button
        binding.btnBack.visibility = View.VISIBLE
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Hide toolbar
        binding.toolbar.visibility = View.GONE

        setupWebView()
        binding.webView.loadUrl("https://chatgpt.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // Modern User Agent to avoid legacy mobile site issues
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        binding.webView.addJavascriptInterface(WebAppInterface(), "Android")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                
                if (url?.contains("chatgpt.com") == true) {
                    // CRITICAL: If on a login or captcha page, show it and don't inject the "hider" script
                    if (url.contains("/auth") || url.contains("/login") || url.contains("/signup") || url.contains("challenges.cloudflare.com")) {
                        binding.webView.visibility = View.VISIBLE
                        binding.progressBar.visibility = View.GONE
                        return
                    }
                    
                    val prompt = intent.getStringExtra(EXTRA_PROMPT)
                    injectMunishScript(prompt)
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
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
        
        binding.webView.evaluateJavascript(munishJs) {
            binding.webView.postDelayed({
                binding.webView.visibility = View.VISIBLE
            }, 100)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}