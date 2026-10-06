package com.example.sunshower.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class RectOverlay(
        val rect: RectF,
        var color: Int,
        val filled: Boolean,
        var strokeFraction: Float
    )

    private var base: Bitmap? = null
    private val displayRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlays = ArrayList<RectOverlay>()
    private var selected: RectOverlay? = null
    private var dragging = false
    private var resizing = false
    private val touchPoint = PointF()
    private val handleRadius = resources.displayMetrics.density * 16f
    private var defaultStrokeFraction = 6f / 200f

    fun setBase(bitmap: Bitmap) {
        base = bitmap
        invalidate()
    }

    fun addOverlay(color: Int) {
        val overlay = RectOverlay(RectF(0.35f, 0.35f, 0.65f, 0.65f), color, true, 0f)
        overlays.add(overlay)
        selected = overlay
        invalidate()
    }

    fun addOutline(color: Int) {
        val overlay =
            RectOverlay(RectF(0.2f, 0.2f, 0.8f, 0.8f), color, false, defaultStrokeFraction)
        overlays.add(overlay)
        selected = overlay
        invalidate()
    }

    fun setStrokeWidth(steps: Int) {
        val fraction = steps / 200f
        defaultStrokeFraction = fraction
        val sel = selected
        if (sel != null && !sel.filled) {
            sel.strokeFraction = fraction
        }
        invalidate()
    }

    fun updateSelectedColor(color: Int) {
        selected?.color = color
        invalidate()
    }

    fun deleteSelected(): Boolean {
        val s = selected ?: return false
        overlays.remove(s)
        selected = null
        invalidate()
        return true
    }

    fun drawOverlays(canvas: Canvas, width: Int, height: Int) {
        val shortSide = minOf(width, height).toFloat()
        for (o in overlays) {
            if (o.filled) {
                fillPaint.color = o.color
                canvas.drawRect(
                    o.rect.left * width,
                    o.rect.top * height,
                    o.rect.right * width,
                    o.rect.bottom * height,
                    fillPaint
                )
            } else {
                strokePaint.color = o.color
                strokePaint.strokeWidth = o.strokeFraction * shortSide
                canvas.drawRect(
                    o.rect.left * width,
                    o.rect.top * height,
                    o.rect.right * width,
                    o.rect.bottom * height,
                    strokePaint
                )
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBase()
    }

    private fun layoutBase() {
        val b = base ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        displayRect.set(
            (width - w) / 2f,
            (height - h) / 2f,
            (width + w) / 2f,
            (height + h) / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        val b = base ?: return
        layoutBase()
        canvas.drawBitmap(b, null, displayRect, null)
        val shortSide = minOf(displayRect.width(), displayRect.height())
        for (o in overlays) {
            val screen = toDisplay(o.rect)
            if (o.filled) {
                fillPaint.color = o.color
                canvas.drawRect(screen, fillPaint)
            } else {
                strokePaint.color = o.color
                strokePaint.strokeWidth = o.strokeFraction * shortSide
                canvas.drawRect(screen, strokePaint)
            }
        }
        selected?.let {
            val corner = bottomRightOf(it)
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = Color.WHITE
            canvas.drawCircle(corner.x, corner.y, handleRadius, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.color = Color.DKGRAY
            handlePaint.strokeWidth = resources.displayMetrics.density * 2f
            canvas.drawCircle(corner.x, corner.y, handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sel = selected
                if (sel != null && near(bottomRightOf(sel), x, y)) {
                    resizing = true
                } else {
                    selected = overlays.lastOrNull { contains(it, x, y) }
                    dragging = selected != null
                }
                touchPoint.set(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (displayRect.width() <= 0f || displayRect.height() <= 0f) return true
                val dx = (x - touchPoint.x) / displayRect.width()
                val dy = (y - touchPoint.y) / displayRect.height()
                touchPoint.set(x, y)
                val sel = selected ?: return true
                if (resizing) {
                    val minRight = sel.rect.left + 0.05f
                    val minBottom = sel.rect.top + 0.05f
                    if (minRight < 1f) {
                        sel.rect.right = (sel.rect.right + dx).coerceIn(minRight, 1f)
                    }
                    if (minBottom < 1f) {
                        sel.rect.bottom = (sel.rect.bottom + dy).coerceIn(minBottom, 1f)
                    }
                } else if (dragging) {
                    translate(sel, dx, dy)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                resizing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun translate(o: RectOverlay, dx: Float, dy: Float) {
        val r = o.rect
        var ndx = dx
        var ndy = dy
        if (r.left + ndx < 0f) ndx = -r.left
        if (r.right + ndx > 1f) ndx = 1f - r.right
        if (r.top + ndy < 0f) ndy = -r.top
        if (r.bottom + ndy > 1f) ndy = 1f - r.bottom
        r.offset(ndx, ndy)
    }

    private fun toDisplay(r: RectF): RectF {
        return RectF(
            displayRect.left + r.left * displayRect.width(),
            displayRect.top + r.top * displayRect.height(),
            displayRect.left + r.right * displayRect.width(),
            displayRect.top + r.bottom * displayRect.height()
        )
    }

    private fun bottomRightOf(o: RectOverlay): PointF {
        return PointF(
            displayRect.left + o.rect.right * displayRect.width(),
            displayRect.top + o.rect.bottom * displayRect.height()
        )
    }

    private fun near(p: PointF, x: Float, y: Float): Boolean {
        val dx = p.x - x
        val dy = p.y - y
        return dx * dx + dy * dy <= handleRadius * handleRadius * 2.5f
    }

    private fun contains(o: RectOverlay, x: Float, y: Float): Boolean {
        if (displayRect.width() <= 0f || displayRect.height() <= 0f) return false
        val nx = (x - displayRect.left) / displayRect.width()
        val ny = (y - displayRect.top) / displayRect.height()
        return o.rect.contains(nx, ny)
    }
}
