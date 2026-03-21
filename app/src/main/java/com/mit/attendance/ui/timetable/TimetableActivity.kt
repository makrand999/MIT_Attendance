package com.mit.attendance.ui.timetable

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityTimetableBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class TimetableActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimetableBinding
    private lateinit var prefs: UserPreferences

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var currentRotation = 0

    private lateinit var scaleDetector: ScaleGestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { saveAndLoadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Timetable"

        prefs = UserPreferences(this)

        setupTouchInteraction()
        loadImage()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchInteraction() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                binding.timetableImage.imageMatrix = matrix
                return true
            }
        })

        binding.timetableImage.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(event.x, event.y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(mid, event)
                        mode = ZOOM
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                    if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - start.x, event.y - start.y)
                    }
                }
            }

            binding.timetableImage.imageMatrix = matrix
            true
        }
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private fun saveAndLoadImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val file = File(filesDir, "timetable_image.png")
            val outputStream = FileOutputStream(file)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            
            lifecycleScope.launch {
                prefs.saveTimetableImageUri(file.absolutePath)
                displayImage(file)
                Toast.makeText(this@TimetableActivity, "Timetable Image Saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImage() {
        lifecycleScope.launch {
            val path = prefs.timetableImageUri.first()
            currentRotation = prefs.timetableRotation.first()
            
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    displayImage(file)
                } else {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.timetableImage.visibility = View.GONE
                }
            } else {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.timetableImage.visibility = View.GONE
            }
        }
    }

    private fun displayImage(file: File) {
        binding.tvEmpty.visibility = View.GONE
        binding.timetableImage.visibility = View.VISIBLE
        binding.timetableImage.setImageURI(Uri.fromFile(file))
        
        binding.timetableImage.post {
            applyInitialRotation()
        }
    }

    private fun applyInitialRotation() {
        matrix.reset()
        val drawable = binding.timetableImage.drawable ?: return
        val viewWidth = binding.timetableImage.width.toFloat()
        val viewHeight = binding.timetableImage.height.toFloat()
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        // Center rotation
        matrix.postRotate(currentRotation.toFloat(), drawableWidth / 2, drawableHeight / 2)

        // Basic fit center logic after rotation
        val rect = android.graphics.RectF(0f, 0f, drawableWidth, drawableHeight)
        matrix.mapRect(rect)
        val scale = Math.min(viewWidth / rect.width(), viewHeight / rect.height())
        matrix.postScale(scale, scale)
        
        val rectScaled = android.graphics.RectF(0f, 0f, drawableWidth, drawableHeight)
        matrix.mapRect(rectScaled)
        matrix.postTranslate((viewWidth - rectScaled.width()) / 2 - rectScaled.left, (viewHeight - rectScaled.height()) / 2 - rectScaled.top)

        binding.timetableImage.imageMatrix = matrix
    }

    private fun rotateImage() {
        currentRotation = (currentRotation + 90) % 360
        lifecycleScope.launch {
            prefs.saveTimetableRotation(currentRotation)
            applyInitialRotation()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_timetable, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_rotate -> {
                rotateImage()
                true
            }
            R.id.action_import -> {
                imagePicker.launch("image/*")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
