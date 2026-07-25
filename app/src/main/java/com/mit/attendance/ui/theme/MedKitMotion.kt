package com.mit.attendance.ui.theme

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R

object MedKitMotion {

    fun playEntrance(vararg views: View) {
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = view.resources.displayMetrics.density * 20f
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 70L).coerceAtMost(280L))
                .setDuration(380L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    fun applyFloat(view: View, distanceDp: Float = 6f, durationMs: Long = 2600L) {
        val distancePx = distanceDp * view.resources.displayMetrics.density
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -distancePx, 0f).apply {
            duration = durationMs
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    fun applyRecyclerStagger(recyclerView: RecyclerView) {
        recyclerView.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(recyclerView.context, R.anim.medkit_layout_stagger)
        recyclerView.scheduleLayoutAnimation()
    }
}
