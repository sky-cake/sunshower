package org.ayasequart.sunshower.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f
    private var sat = 0.9f
    private var value = 1f

    private val svPaint = Paint()
    private val huePaint = Paint()
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var svBottom = 0f
    private var hueTop = 0f
    private var density = 1f

    val color: Int
        get() = Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun setInitialColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        density = resources.displayMetrics.density
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val extra = (16 * density + 40 * density).toInt()
        setMeasuredDimension(w, w + extra)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        density = resources.displayMetrics.density
        svBottom = w.toFloat()
        hueTop = svBottom + 16 * density
        thumbRing.strokeWidth = 3 * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()

        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        svPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, svBottom, svPaint)
        svPaint.shader = LinearGradient(
            0f, 0f, 0f, svBottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, svBottom, svPaint)
        svPaint.shader = null

        huePaint.shader = LinearGradient(
            0f, hueTop, w, hueTop,
            intArrayOf(
                0xFFFF0000.toInt(),
                0xFFFFFF00.toInt(),
                0xFF00FF00.toInt(),
                0xFF00FFFF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFF00FF.toInt(),
                0xFFFF0000.toInt()
            ),
            floatArrayOf(0f, 1f / 6, 2f / 6, 3f / 6, 4f / 6, 5f / 6, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, hueTop, w, hueTop + 24 * density, huePaint)
        huePaint.shader = null

        val current = color
        drawThumb(canvas, sat * w, (1f - value) * svBottom, current)
        drawThumb(canvas, hue / 360f * w, hueTop + 12 * density, hueColor)
    }

    private fun drawThumb(canvas: Canvas, cx: Float, cy: Float, fill: Int) {
        thumbRing.color = Color.BLACK
        canvas.drawCircle(cx, cy, 11 * density, thumbRing)
        thumbFill.color = fill
        canvas.drawCircle(cx, cy, 9 * density, thumbFill)
        thumbRing.color = Color.WHITE
        canvas.drawCircle(cx, cy, 9 * density, thumbRing)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                if (y <= svBottom) {
                    sat = (x / width.toFloat()).coerceIn(0f, 1f)
                    value = (1f - y / svBottom).coerceIn(0f, 1f)
                } else if (y >= hueTop) {
                    hue = (x / width.toFloat() * 360f).coerceIn(0f, 360f)
                }
                invalidate()
                onColorChanged?.invoke(color)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}