package com.mit.attendance.campus

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.mit.attendance.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Only opened by native campus screens; fetches one protected image on demand. */
class CampusImageActivity : AppCompatActivity() {
    private lateinit var session: CampusSession
    private lateinit var image: ImageView
    private lateinit var loading: ProgressBar
    private lateinit var failure: LinearLayout
    private var images = emptyList<String>()
    private var position = 0
    private var request: Job? = null
    private lateinit var counter: android.widget.TextView
    private lateinit var previous: View
    private lateinit var next: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = CampusSessionStore(this).read()
        if (current == null || current.profile.id != intent.getStringExtra("account") ||
            current.generation != intent.getStringExtra("generation") || current.selectedClass != intent.getStringExtra("class")) {
            finish(); return
        }
        session = current
        images = intent.getStringArrayListExtra("images").orEmpty()
            .filter { it.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) }.distinct()
        if (images.isEmpty()) { finish(); return }
        position = (savedInstanceState?.getInt("position") ?: 0).coerceIn(images.indices)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@CampusImageActivity, R.color.medkit_cream))
        }
        val controls = CampusUi.column(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        CampusUi.button(this, controls, "Close") { finish() }
        counter = CampusUi.text(this, "").also {
            it.gravity = Gravity.CENTER
            controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }
        shell.addView(controls)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = false }
        loading = ProgressBar(this).apply { contentDescription = "Loading image" }
        failure = CampusUi.column(this).apply {
            gravity = Gravity.CENTER
            addView(CampusUi.text(this@CampusImageActivity, "Image unavailable"))
            CampusUi.button(this@CampusImageActivity, this, "Retry") { load() }
            visibility = View.GONE
        }
        shell.addView(FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(CampusUi.dp(this@CampusImageActivity, 40), CampusUi.dp(this@CampusImageActivity, 40), Gravity.CENTER))
            addView(failure, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val paging = CampusUi.column(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        previous = CampusUi.button(this, paging, "Previous") { position--; load() }
        next = CampusUi.button(this, paging, "Next") { position++; load() }
        paging.visibility = if (images.size > 1) View.VISIBLE else View.GONE
        shell.addView(paging)
        setContentView(shell)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        load()
    }

    private fun currentAccount(): Boolean = CampusSessionStore(this).read()?.let {
        it.profile.id == session.profile.id && it.generation == session.generation && it.selectedClass == session.selectedClass
    } == true

    override fun onStart() {
        super.onStart()
        if (::session.isInitialized && !currentAccount()) finish()
    }

    private fun load() {
        request?.cancel()
        Glide.with(this).clear(image)
        image.setImageDrawable(null)
        image.visibility = View.INVISIBLE
        failure.visibility = View.GONE
        loading.visibility = View.VISIBLE
        counter.text = "${position + 1} / ${images.size}"
        image.contentDescription = "Image ${position + 1} of ${images.size}"
        previous.isEnabled = position > 0
        next.isEnabled = position < images.lastIndex
        val selected = position
        request = lifecycleScope.launch {
            try {
                val bytes = CampusTransport.authenticated(this@CampusImageActivity, session).image(images[selected])
                if (!currentAccount()) { finish(); return@launch }
                if (position != selected) return@launch
                Glide.with(this@CampusImageActivity).load(bytes)
                    .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).fitCenter()
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                            if (position == selected) showFailure()
                            return false
                        }
                        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                            if (!currentAccount()) { finish(); return true }
                            if (position == selected) {
                                loading.visibility = View.GONE
                                image.visibility = View.VISIBLE
                            }
                            return false
                        }
                    }).into(image)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (!currentAccount()) finish() else if (position == selected) showFailure()
            }
        }
    }

    private fun showFailure() {
        loading.visibility = View.GONE
        image.visibility = View.INVISIBLE
        failure.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("position", position)
        super.onSaveInstanceState(outState)
    }
}
