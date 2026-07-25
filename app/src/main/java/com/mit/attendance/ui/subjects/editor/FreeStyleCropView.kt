package com.mit.attendance.ui.subjects.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeStyleCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class DragMode {
        NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3000000") }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var sourceBitmap: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private val displayRect = RectF()
    private val cropRect = RectF()
    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    init {
        framePaint.strokeWidth = dp(2f)
        gridPaint.strokeWidth = dp(1f)
    }

    var minCropSizePx: Float = dp(8f)
        set(value) {
            field = value.coerceAtLeast(1f)
            clampCropRect()
            invalidate()
        }

    var frameStrokeWidthPx: Float = dp(2f)
        set(value) {
            field = value.coerceAtLeast(0f)
            framePaint.strokeWidth = field
            invalidate()
        }

    var gridStrokeWidthPx: Float = dp(1f)
        set(value) {
            field = value.coerceAtLeast(0f)
            gridPaint.strokeWidth = field
            invalidate()
        }

    var handleRadiusPx: Float = dp(12f)
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showFrame: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun setImage(bitmap: Bitmap, width: Int, height: Int) {
        sourceBitmap = bitmap
        sourceWidth = width
        sourceHeight = height
        configureRects()
        invalidate()
    }

    fun getCropRegion(): RectF {
        if (displayRect.width() <= 0f || displayRect.height() <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
            return RectF()
        }

        val left = ((cropRect.left - displayRect.left) / displayRect.width()) * sourceWidth
        val top = ((cropRect.top - displayRect.top) / displayRect.height()) * sourceHeight
        val right = ((cropRect.right - displayRect.left) / displayRect.width()) * sourceWidth
        val bottom = ((cropRect.bottom - displayRect.top) / displayRect.height()) * sourceHeight

        return RectF(
            left.coerceIn(0f, sourceWidth.toFloat()),
            top.coerceIn(0f, sourceHeight.toFloat()),
            right.coerceIn(0f, sourceWidth.toFloat()),
            bottom.coerceIn(0f, sourceHeight.toFloat())
        )
    }

    fun setCropRegion(left: Float, top: Float, right: Float, bottom: Float) {
        cropRect.set(left, top, right, bottom)
        clampCropRect()
        invalidate()
    }

    fun resetCropRegion() {
        configureRects()
        invalidate()
    }

    private fun configureRects() {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return
        displayRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val viewRatio = width.toFloat() / height.toFloat()
        val imageRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
        if (imageRatio > viewRatio) {
            val drawnHeight = width / imageRatio
            val top = (height - drawnHeight) / 2f
            displayRect.set(0f, top, width.toFloat(), top + drawnHeight)
        } else {
            val drawnWidth = height * imageRatio
            val left = (width - drawnWidth) / 2f
            displayRect.set(left, 0f, left + drawnWidth, height.toFloat())
        }

        if (cropRect.isEmpty()) {
            val insetX = displayRect.width() * 0.18f
            val insetY = displayRect.height() * 0.18f
            cropRect.set(
                displayRect.left + insetX,
                displayRect.top + insetY,
                displayRect.right - insetX,
                displayRect.bottom - insetY
            )
        } else {
            clampCropRect()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configureRects()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        sourceBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, displayRect, bitmapPaint)
        }

        drawDimmedLayer(canvas)
        if (showFrame) {
            canvas.drawRect(cropRect, framePaint)
        }
        if (showGrid) {
            drawGrid(canvas)
        }
        drawHandles(canvas)
    }

    private fun drawDimmedLayer(canvas: Canvas) {
        canvas.save()
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val firstX = cropRect.left + cropRect.width() / 3f
        val secondX = cropRect.left + cropRect.width() * 2f / 3f
        val firstY = cropRect.top + cropRect.height() / 3f
        val secondY = cropRect.top + cropRect.height() * 2f / 3f
        canvas.drawLine(firstX, cropRect.top, firstX, cropRect.bottom, gridPaint)
        canvas.drawLine(secondX, cropRect.top, secondX, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, firstY, cropRect.right, firstY, gridPaint)
        canvas.drawLine(cropRect.left, secondY, cropRect.right, secondY, gridPaint)
    }

    private fun drawHandles(canvas: Canvas) {
        val radius = handleRadiusPx
        val points = listOf(
            cropRect.left to cropRect.top,
            cropRect.right to cropRect.top,
            cropRect.left to cropRect.bottom,
            cropRect.right to cropRect.bottom
        )
        points.forEach { (x, y) ->
            canvas.drawCircle(x, y, radius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sourceBitmap == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragMode = detectDragMode(event.x, event.y)
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (dragMode) {
                    DragMode.MOVE -> cropRect.offset(dx, dy)
                    DragMode.TOP_LEFT -> {
                        cropRect.left += dx
                        cropRect.top += dy
                    }
                    DragMode.TOP_RIGHT -> {
                        cropRect.right += dx
                        cropRect.top += dy
                    }
                    DragMode.BOTTOM_LEFT -> {
                        cropRect.left += dx
                        cropRect.bottom += dy
                    }
                    DragMode.BOTTOM_RIGHT -> {
                        cropRect.right += dx
                        cropRect.bottom += dy
                    }
                    DragMode.LEFT -> cropRect.left += dx
                    DragMode.TOP -> cropRect.top += dy
                    DragMode.RIGHT -> cropRect.right += dx
                    DragMode.BOTTOM -> cropRect.bottom += dy
                    DragMode.NONE -> Unit
                }
                lastX = event.x
                lastY = event.y
                clampCropRect()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        val radius = handleRadiusPx * 1.8f
        if (distance(x, y, cropRect.left, cropRect.top) <= radius) return DragMode.TOP_LEFT
        if (distance(x, y, cropRect.right, cropRect.top) <= radius) return DragMode.TOP_RIGHT
        if (distance(x, y, cropRect.left, cropRect.bottom) <= radius) return DragMode.BOTTOM_LEFT
        if (distance(x, y, cropRect.right, cropRect.bottom) <= radius) return DragMode.BOTTOM_RIGHT

        val edgeBand = max(handleRadiusPx, dp(16f))
        if (insideVerticalBand(x, cropRect.left, edgeBand) && insideHorizontalRange(y, cropRect.top, cropRect.bottom)) return DragMode.LEFT
        if (insideVerticalBand(x, cropRect.right, edgeBand) && insideHorizontalRange(y, cropRect.top, cropRect.bottom)) return DragMode.RIGHT
        if (insideHorizontalBand(y, cropRect.top, edgeBand) && insideVerticalRange(x, cropRect.left, cropRect.right)) return DragMode.TOP
        if (insideHorizontalBand(y, cropRect.bottom, edgeBand) && insideVerticalRange(x, cropRect.left, cropRect.right)) return DragMode.BOTTOM

        return if (cropRect.contains(x, y)) DragMode.MOVE else DragMode.NONE
    }

    private fun clampCropRect() {
        if (displayRect.width() <= 0f || displayRect.height() <= 0f) return

        var left = cropRect.left
        var top = cropRect.top
        var right = cropRect.right
        var bottom = cropRect.bottom

        if (right - left < minCropSizePx) {
            right = left + minCropSizePx
        }
        if (bottom - top < minCropSizePx) {
            bottom = top + minCropSizePx
        }

        if (left < displayRect.left) {
            val delta = displayRect.left - left
            left += delta
            right += delta
        }
        if (top < displayRect.top) {
            val delta = displayRect.top - top
            top += delta
            bottom += delta
        }
        if (right > displayRect.right) {
            val delta = right - displayRect.right
            left -= delta
            right -= delta
        }
        if (bottom > displayRect.bottom) {
            val delta = bottom - displayRect.bottom
            top -= delta
            bottom -= delta
        }

        if (right - left < minCropSizePx) {
            right = min(displayRect.right, left + minCropSizePx)
            left = max(displayRect.left, right - minCropSizePx)
        }
        if (bottom - top < minCropSizePx) {
            bottom = min(displayRect.bottom, top + minCropSizePx)
            top = max(displayRect.top, bottom - minCropSizePx)
        }

        cropRect.set(left, top, right, bottom)
    }

    private fun insideVerticalBand(x: Float, anchor: Float, band: Float): Boolean =
        x in (anchor - band)..(anchor + band)

    private fun insideHorizontalBand(y: Float, anchor: Float, band: Float): Boolean =
        y in (anchor - band)..(anchor + band)

    private fun insideHorizontalRange(y: Float, top: Float, bottom: Float): Boolean =
        y in top..bottom

    private fun insideVerticalRange(x: Float, left: Float, right: Float): Boolean =
        x in left..right

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
