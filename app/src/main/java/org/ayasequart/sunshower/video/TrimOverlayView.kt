package org.ayasequart.sunshower.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class TrimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onTrimChanged(startFraction: Float, endFraction: Float, activeFraction: Float)
    }

    var listener: Listener? = null

    private val barRect = RectF()
    private val dimPaint = Paint()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleWidth = resources.displayMetrics.density * 10f
    private val handleHeight = resources.displayMetrics.density * 26f
    private val handleCorner = resources.displayMetrics.density * 3f

    private var dragHandle = -1
    private var startFraction = 0f
    private var endFraction = 1f
    private var hasVideo = false

    fun setDuration(durationUs: Long) {
        hasVideo = durationUs > 0
        startFraction = 0f
        endFraction = 1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBar()
    }

    private fun layoutBar() {
        val density = resources.displayMetrics.density
        val sidePadding = 24 * density
        val verticalMargin = 12 * density
        val barHeight = 16 * density
        val y = height - verticalMargin - barHeight
        barRect.set(sidePadding, y, width - sidePadding, y + barHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasVideo || barRect.width() <= 0f) return
        val density = resources.displayMetrics.density

        barPaint.color = BAR_COLOR
        canvas.drawRoundRect(barRect, 4 * density, 4 * density, barPaint)

        val selLeft = barRect.left + startFraction * barRect.width()
        val selRight = barRect.left + endFraction * barRect.width()
        selectedPaint.color = SELECTED_COLOR
        canvas.drawRoundRect(
            RectF(selLeft, barRect.top, selRight, barRect.bottom),
            4 * density, 4 * density, selectedPaint
        )

        dimPaint.color = DIM_COLOR
        canvas.drawRect(barRect.left, barRect.top, selLeft, barRect.bottom, dimPaint)
        canvas.drawRect(selRight, barRect.top, barRect.right, barRect.bottom, dimPaint)

        drawHandle(canvas, selLeft)
        drawHandle(canvas, selRight)
    }

    private fun drawHandle(canvas: Canvas, x: Float) {
        val rect = RectF(
            x - handleWidth / 2f,
            barRect.centerY() - handleHeight / 2f,
            x + handleWidth / 2f,
            barRect.centerY() + handleHeight / 2f
        )
        handlePaint.style = Paint.Style.FILL
        handlePaint.color = Color.WHITE
        canvas.drawRoundRect(rect, handleCorner, handleCorner, handlePaint)
        handlePaint.style = Paint.Style.STROKE
        handlePaint.color = Color.DKGRAY
        handlePaint.strokeWidth = resources.displayMetrics.density * 2f
        canvas.drawRoundRect(rect, handleCorner, handleCorner, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasVideo || barRect.width() <= 0f) return false
        val x = event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dStart = Math.abs(x - barLeft(startFraction))
                val dEnd = Math.abs(x - barLeft(endFraction))
                dragHandle = if (dStart <= dEnd) HANDLE_START else HANDLE_END
                updateTrim(x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateTrim(x)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragHandle = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateTrim(x: Float) {
        val fraction = xToFraction(x)
        when (dragHandle) {
            HANDLE_START -> startFraction = fraction.coerceIn(0f, endFraction - MIN_RANGE)
            HANDLE_END -> endFraction = fraction.coerceIn(startFraction + MIN_RANGE, 1f)
            else -> return
        }
        invalidate()
        val active = if (dragHandle == HANDLE_START) startFraction else endFraction
        listener?.onTrimChanged(startFraction, endFraction, active)
    }

    private fun barLeft(fraction: Float): Float =
        barRect.left + fraction * barRect.width()

    private fun xToFraction(x: Float): Float =
        ((x - barRect.left) / barRect.width()).coerceIn(0f, 1f)

    companion object {
        private const val HANDLE_START = 0
        private const val HANDLE_END = 1
        private const val MIN_RANGE = 0.02f
        private const val BAR_COLOR = 0xFF444444.toInt()
        private const val SELECTED_COLOR = 0xFF3E5876.toInt()
        private const val DIM_COLOR = 0x66000000
    }
}
