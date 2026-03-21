package com.mit.attendance.auth


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// ─────────────────────────────────────────
//  LOGIN ACTIVITY
//
//  Shows a full-screen WebView with ChatGPT's login page.
//  User logs in manually (Google, email, Microsoft, etc.)
//  On success: saves cookies → returns RESULT_OK to caller.
//  On failure: returns RESULT_CANCELED.
//
//  How to launch from your main Activity/Fragment:
//
//    // Check login state first:
//    if (!ChatGptLoginManager(this).isLoggedIn()) {
//        startActivityForResult(
//            Intent(this, LoginActivity::class.java),
//            REQUEST_LOGIN
//        )
//    }
//
//    // Handle result:
//    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
//        if (req == REQUEST_LOGIN && res == RESULT_OK) {
//            // Logged in — start agent
//        }
//    }
//
//  companion object { const val REQUEST_LOGIN = 1001 }
// ─────────────────────────────────────────
class LoginActivity : AppCompatActivity() {

    private lateinit var loginManager: ChatGptLoginManager
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loginManager = ChatGptLoginManager(this)

        // ── Build layout in code (no XML needed) ──────────────────
        val root = FrameLayout(this)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(webView)

        // Overlay: spinner + status shown while detecting login
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.gravity = android.view.Gravity.CENTER }
            isIndeterminate = true
        }

        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = android.view.Gravity.CENTER
                it.topMargin = 180
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            text = "Detecting login…"
        }

        retryButton = Button(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = 80
            }
            text = "Retry"
            visibility = View.GONE
            setOnClickListener { startLogin() }
        }

        overlay.addView(progressBar)
        overlay.addView(statusText)
        overlay.addView(retryButton)
        root.addView(overlay)

        setContentView(root)

        // Start login flow
        startLogin()
    }

    private fun startLogin() {
        retryButton.visibility = View.GONE

        loginManager.startLoginFlow(
            loginWebView = webView,
            onSuccess = {
                // ✅ Login succeeded — cookies saved by LoginManager
                // Verify them once before returning to caller
                statusText.text = "Verifying session…"

                loginManager.verifyCookies(
                    webView   = webView,
                    onValid   = {
                        setResult(RESULT_OK)
                        finish()
                    },
                    onExpired = {
                        // Shouldn't happen right after login, but handle gracefully
                        showError("Session check failed — please try again")
                    }
                )
            },
            onError = { error ->
                showError(error)
            }
        )
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        statusText.text = "⚠️ $message"
        retryButton.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        // Save cookies whenever app goes to background
        // in case the session was updated
        loginManager.saveCookiesFrom(webView)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val REQUEST_LOGIN = 1001

        fun launch(context: Context) {
            context.startActivity(Intent(context, LoginActivity::class.java))
        }
    }
}