package com.mit.attendance.ui.subjects

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.*

object WebViewWindowManager {

    private val hiddenWebViews = Stack<View>()
    private var currentVisibleWebView: View? = null
    private var isAnimating = false

    private var startX = 0f
    private var startY = 0f
    private val SWIPE_THRESHOLD = 100
    private var screenHeight = 0
    private var screenWidth = 0
    private var isSwiping = false

    fun init(activity: Activity) {
        val metrics = activity.resources.displayMetrics
        screenHeight = metrics.heightPixels
        screenWidth = metrics.widthPixels
    }

    fun isWebViewVisible(): Boolean {
        return currentVisibleWebView != null
    }

    /**
     * Handles touch events from Activity.dispatchTouchEvent.
     * Returns true if the event was consumed by the swipe gesture.
     */
    fun handleTouchEvent(event: MotionEvent, container: FrameLayout?): Boolean {
        if (container == null) return false
        
        // Only reserve the area and handle swipes if there are WebViews to manage
        if (currentVisibleWebView == null && hiddenWebViews.isEmpty()) return false

        // Match NotesActivity's bottom area for consistency
        val bottomAreaStart = screenHeight * 0.95

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isSwiping = false
                // Do not consume DOWN, allow it to pass to children to support taps
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (startY >= bottomAreaStart && !isSwiping) {
                    val diffX = event.x - startX
                    val diffY = event.y - startY
                    // Only start consuming if it's clearly a horizontal swipe
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(diffX) > Math.abs(diffY)) {
                        isSwiping = true
                    }
                }
                return isSwiping
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSwiping = isSwiping
                if (wasSwiping && event.action == MotionEvent.ACTION_UP && startY >= bottomAreaStart) {
                    val diffX = event.x - startX
                    val diffY = event.y - startY
                    
                    if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD) {
                        if (diffX > 0) {
                            // Left to Right: Hide current webview to the right edge
                            hideCurrentWebView(container)
                        } else {
                            // Right to Left: Cycle through hidden webviews
                            if (!hiddenWebViews.isEmpty()) {
                                showLastHiddenWebView(container)
                            }
                        }
                    }
                }
                isSwiping = false
                return wasSwiping
            }
        }
        return false
    }

    fun onActivityResumed(container: FrameLayout) {
        currentVisibleWebView?.let { view ->
            val parent = view.parent as? ViewGroup
            if (parent != container) {
                parent?.removeView(view)
                container.addView(view)
            }
            view.visibility = View.VISIBLE
            view.translationX = 0f
        }
    }

    fun addWebView(container: FrameLayout, webViewLayout: View) {
        val oldView = currentVisibleWebView
        isAnimating = true
        
        currentVisibleWebView = webViewLayout
        if (webViewLayout.parent != container) {
            (webViewLayout.parent as? ViewGroup)?.removeView(webViewLayout)
            container.addView(webViewLayout)
        }
        webViewLayout.visibility = View.VISIBLE
        
        webViewLayout.translationX = if (container.width > 0) container.width.toFloat() else screenWidth.toFloat()
        
        webViewLayout.animate()
            .translationX(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    oldView?.let {
                        it.visibility = View.GONE
                        (it.parent as? ViewGroup)?.removeView(it)
                        hiddenWebViews.push(it)
                    }
                    isAnimating = false
                }
            })
            .start()
    }

    private fun hideCurrentWebView(container: FrameLayout) {
        val view = currentVisibleWebView ?: return
        if (isAnimating) return
        
        isAnimating = true
        view.animate()
            .translationX(if (container.width > 0) container.width.toFloat() else screenWidth.toFloat())
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    container.removeView(view)
                    hiddenWebViews.push(view)
                    currentVisibleWebView = null
                    isAnimating = false
                }
            })
            .start()
    }

    private fun showLastHiddenWebView(container: FrameLayout) {
        if (hiddenWebViews.isEmpty() || isAnimating) return
        
        isAnimating = true
        val oldView = currentVisibleWebView

        // Pop the next one to show
        val nextView = hiddenWebViews.pop()
        currentVisibleWebView = nextView

        if (nextView.parent != container) {
            (nextView.parent as? ViewGroup)?.removeView(nextView)
            container.addView(nextView)
        }
        nextView.visibility = View.VISIBLE
        nextView.translationX = if (container.width > 0) container.width.toFloat() else screenWidth.toFloat()
        
        nextView.animate()
            .translationX(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    oldView?.let {
                        it.visibility = View.GONE
                        (it.parent as? ViewGroup)?.removeView(it)
                        hiddenWebViews.add(0, it)
                    }
                    isAnimating = false
                }
            })
            .start()
    }

    fun onViewRemoved(view: View) {
        if (currentVisibleWebView == view) {
            currentVisibleWebView = null
        }
        hiddenWebViews.remove(view)
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
