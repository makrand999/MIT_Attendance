package com.mit.attendance.ui.subjects.editor

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.databinding.ActivityEditorCropBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class EditorCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_PATH = "editor_crop_source_path"
        const val EXTRA_OUTPUT_PATH = "editor_crop_output_path"
        const val EXTRA_RESULT_LEFT = "editor_crop_result_left"
        const val EXTRA_RESULT_TOP = "editor_crop_result_top"
        const val EXTRA_RESULT_WIDTH = "editor_crop_result_width"
        const val EXTRA_RESULT_HEIGHT = "editor_crop_result_height"
        const val EXTRA_RESULT_SOURCE_WIDTH = "editor_crop_result_source_width"
        const val EXTRA_RESULT_SOURCE_HEIGHT = "editor_crop_result_source_height"
    }

    private lateinit var binding: ActivityEditorCropBinding
    private var sourcePath: String? = null
    private var outputPath: String? = null
    private var sourceWidth = 0
    private var sourceHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)

        if (sourcePath.isNullOrBlank() || outputPath.isNullOrBlank()) {
            Toast.makeText(this, "Missing crop source", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { cancelCrop() }

        binding.btnCrop.setOnClickListener { performCrop() }
        binding.btnReset.setOnClickListener { binding.cropView.resetCropRegion() }
        binding.btnBorder.setOnClickListener { showBorderDialog() }

        loadPreviewBitmap(sourcePath!!)
    }

    private fun loadPreviewBitmap(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                sourceWidth = bounds.outWidth
                sourceHeight = bounds.outHeight
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    throw IllegalStateException("Invalid image")
                }

                val preview = decodeSampledBitmap(path, maxDimension = 2048)
                withContext(Dispatchers.Main) {
                    binding.cropView.setImage(preview, sourceWidth, sourceHeight)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorCropActivity, "Could not load image: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun decodeSampledBitmap(path: String, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        var sampleSize = 1
        while ((bounds.outWidth / sampleSize) > maxDimension || (bounds.outHeight / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
            ?: throw IllegalStateException("Could not decode preview")
    }

    private fun showBorderDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }

        fun numberField(label: String, value: String): EditText {
            container.addView(TextView(this).apply {
                text = label
                setPadding(0, 14, 0, 8)
            })
            return EditText(this).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setBackgroundResource(com.mit.attendance.R.drawable.bg_edit_text)
                setPadding(24, 18, 24, 18)
            }.also { container.addView(it) }
        }

        val frameInput = numberField("Crop border thickness", binding.cropView.frameStrokeWidthPx.toString())
        val handleInput = numberField("Handle size", binding.cropView.handleRadiusPx.toString())
        val minSizeInput = numberField("Minimum crop size", binding.cropView.minCropSizePx.toString())
        val gridInput = CheckBox(this).apply {
            text = "Show grid lines"
            isChecked = binding.cropView.showGrid
        }
        container.addView(gridInput)

        AlertDialog.Builder(this)
            .setTitle("Crop Border")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Apply") { _, _ ->
                binding.cropView.frameStrokeWidthPx = frameInput.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: binding.cropView.frameStrokeWidthPx
                binding.cropView.handleRadiusPx = handleInput.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: binding.cropView.handleRadiusPx
                binding.cropView.minCropSizePx = minSizeInput.text.toString().toFloatOrNull()?.coerceAtLeast(1f) ?: binding.cropView.minCropSizePx
                binding.cropView.showGrid = gridInput.isChecked
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performCrop() {
        val region = binding.cropView.getCropRegion()
        val left = region.left.roundToInt().coerceIn(0, max(sourceWidth - 1, 0))
        val top = region.top.roundToInt().coerceIn(0, max(sourceHeight - 1, 0))
        val right = region.right.roundToInt().coerceIn(left + 1, sourceWidth)
        val bottom = region.bottom.roundToInt().coerceIn(top + 1, sourceHeight)
        val safeRect = android.graphics.Rect(left, top, right, bottom)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val decoder = BitmapRegionDecoder.newInstance(requireNotNull(sourcePath), false)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val cropped = decoder.decodeRegion(safeRect, options)
                    ?: throw IllegalStateException("Could not crop image")

                val outFile = File(requireNotNull(outputPath))
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                withContext(Dispatchers.Main) {
                    val result = Intent().apply {
                        putExtra(EXTRA_OUTPUT_PATH, outFile.absolutePath)
                        putExtra(EXTRA_RESULT_LEFT, safeRect.left)
                        putExtra(EXTRA_RESULT_TOP, safeRect.top)
                        putExtra(EXTRA_RESULT_WIDTH, safeRect.width())
                        putExtra(EXTRA_RESULT_HEIGHT, safeRect.height())
                        putExtra(EXTRA_RESULT_SOURCE_WIDTH, sourceWidth)
                        putExtra(EXTRA_RESULT_SOURCE_HEIGHT, sourceHeight)
                    }
                    setResult(RESULT_OK, result)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorCropActivity, "Crop failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cancelCrop() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
