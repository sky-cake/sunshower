package org.ayasequart.sunshower.edit

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
import android.view.ViewGroup

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
    private val dimPaint = Paint()
    private val cropStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val overlays = ArrayList<RectOverlay>()
    private var selected: RectOverlay? = null
    private var dragging = false
    private var resizing = false
    private val touchPoint = PointF()
    private val handleRadius = resources.displayMetrics.density * 16f
    private var defaultStrokeFraction = 6f / 200f

    private var cropMode = false
    private val cropRect = RectF(0f, 0f, 1f, 1f)
    private var cropAspect: Float? = null
    private var cropResizeCorner = -1
    private var cropDragging = false

    fun beginCrop() {
        cropMode = true
        cropRect.set(0f, 0f, 1f, 1f)
        cropAspect = null
        selected = null
        invalidate()
    }

    fun isCropMode(): Boolean = cropMode

    fun setCropAspect(aspect: Float?) {
        cropAspect = aspect
        if (aspect != null) {
            val b = base ?: return
            val bw = b.width.toFloat()
            val bh = b.height.toFloat()
            val cx = cropRect.centerX()
            val cy = cropRect.centerY()
            var w = cropRect.width() * bw
            var h = w / aspect
            if (h > bh) {
                h = bh
                w = h * aspect
            }
            var nw = w / bw
            var nh = h / bh
            nw = nw.coerceAtMost(1f)
            nh = nh.coerceAtMost(1f)
            cropRect.set(
                (cx - nw / 2).coerceIn(0f, 1f - nw),
                (cy - nh / 2).coerceIn(0f, 1f - nh),
                0f,
                0f
            )
            cropRect.right = cropRect.left + nw
            cropRect.bottom = cropRect.top + nh
        }
        invalidate()
    }

    fun cancelCrop() {
        cropMode = false
        cropDragging = false
        cropResizeCorner = -1
        invalidate()
    }

    fun applyCrop(): RectF {
        val c = RectF(cropRect)
        val changed = c.width() < 0.999f || c.height() < 0.999f
        if (changed) {
            val it = overlays.iterator()
            while (it.hasNext()) {
                val r = it.next().rect
                if (r.right <= c.left + 1e-4f || r.left >= c.right - 1e-4f ||
                    r.bottom <= c.top + 1e-4f || r.top >= c.bottom - 1e-4f
                ) {
                    it.remove()
                    continue
                }
                val nl = ((r.left - c.left) / c.width()).coerceIn(0f, 1f)
                val nt = ((r.top - c.top) / c.height()).coerceIn(0f, 1f)
                val nr = ((r.right - c.left) / c.width()).coerceIn(0f, 1f)
                val nb = ((r.bottom - c.top) / c.height()).coerceIn(0f, 1f)
                r.set(nl, nt, nr, nb)
            }
        }
        cropMode = false
        cropDragging = false
        cropResizeCorner = -1
        invalidate()
        return c
    }

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
        if (cropMode) {
            drawCropOverlay(canvas)
            return
        }
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
            drawHandle(canvas, bottomRightOf(it))
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val c = toDisplay(cropRect)
        dimPaint.color = DIM_COLOR
        canvas.drawRect(displayRect.left, displayRect.top, displayRect.right, c.top, dimPaint)
        canvas.drawRect(displayRect.left, c.bottom, displayRect.right, displayRect.bottom, dimPaint)
        canvas.drawRect(displayRect.left, c.top, c.left, c.bottom, dimPaint)
        canvas.drawRect(c.right, c.top, displayRect.right, c.bottom, dimPaint)
        cropStrokePaint.strokeWidth = resources.displayMetrics.density * 2f
        canvas.drawRect(c, cropStrokePaint)
        for (corner in cropCorners()) {
            drawHandle(canvas, corner)
        }
    }

    private fun cropCorners(): List<PointF> {
        val c = toDisplay(cropRect)
        return listOf(
            PointF(c.left, c.top),
            PointF(c.right, c.top),
            PointF(c.right, c.bottom),
            PointF(c.left, c.bottom)
        )
    }

    private fun drawHandle(canvas: Canvas, point: PointF) {
        handlePaint.style = Paint.Style.FILL
        handlePaint.color = Color.WHITE
        canvas.drawCircle(point.x, point.y, handleRadius, handlePaint)
        handlePaint.style = Paint.Style.STROKE
        handlePaint.color = Color.DKGRAY
        handlePaint.strokeWidth = resources.displayMetrics.density * 2f
        canvas.drawCircle(point.x, point.y, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        if (cropMode) {
            return onTouchCrop(event, x, y)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sel = selected
                if (sel != null && near(bottomRightOf(sel), x, y)) {
                    resizing = true
                    touchPoint.set(x, y)
                    invalidate()
                    return true
                }
                selected = overlays.lastOrNull { contains(it, x, y) }
                dragging = selected != null
                touchPoint.set(x, y)
                invalidate()
                return dragging
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

    private fun onTouchCrop(event: MotionEvent, x: Float, y: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                cropResizeCorner = -1
                cropDragging = false
                val corners = cropCorners()
                for (i in corners.indices) {
                    if (near(corners[i], x, y)) {
                        cropResizeCorner = i
                        break
                    }
                }
                if (cropResizeCorner < 0) {
                    val n = toNormalized(PointF(x, y))
                    if (cropRect.contains(n.x, n.y)) {
                        cropDragging = true
                    }
                }
                touchPoint.set(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (displayRect.width() <= 0f || displayRect.height() <= 0f) return true
                if (cropResizeCorner >= 0) {
                    resizeCropCorner(x, y)
                } else if (cropDragging) {
                    val dx = (x - touchPoint.x) / displayRect.width()
                    val dy = (y - touchPoint.y) / displayRect.height()
                    var ndx = dx
                    var ndy = dy
                    if (cropRect.left + ndx < 0f) ndx = -cropRect.left
                    if (cropRect.right + ndx > 1f) ndx = 1f - cropRect.right
                    if (cropRect.top + ndy < 0f) ndy = -cropRect.top
                    if (cropRect.bottom + ndy > 1f) ndy = 1f - cropRect.bottom
                    cropRect.offset(ndx, ndy)
                }
                touchPoint.set(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cropDragging = false
                cropResizeCorner = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resizeCropCorner(x: Float, y: Float) {
        val b = base ?: return
        val n = toNormalized(PointF(x, y))
        val nx = n.x.coerceIn(0f, 1f)
        val ny = n.y.coerceIn(0f, 1f)
        val minSize = 0.05f
        val corner = cropResizeCorner
        val ax = if (corner == 0 || corner == 3) cropRect.right else cropRect.left
        val ay = if (corner == 2 || corner == 3) cropRect.top else cropRect.bottom
        val sx = if (corner == 0 || corner == 3) -1f else 1f
        val sy = if (corner == 0 || corner == 1) -1f else 1f
        var w = ((nx - ax) * sx).coerceAtLeast(minSize)
        var h = ((ny - ay) * sy).coerceAtLeast(minSize)
        val maxW = if (sx > 0) 1f - ax else ax
        val maxH = if (sy > 0) 1f - ay else ay
        val aspect = cropAspect
        if (aspect != null) {
            val bw = b.width.toFloat()
            val bh = b.height.toFloat()
            var pw = minOf(w * bw, h * bh * aspect)
            var ph = pw / aspect
            val maxPw = maxW * bw
            val maxPh = maxH * bh
            if (pw > maxPw) {
                pw = maxPw
                ph = pw / aspect
            }
            if (ph > maxPh) {
                ph = maxPh
                pw = ph * aspect
                if (pw > maxPw) {
                    pw = maxPw
                    ph = pw / aspect
                }
            }
            w = pw / bw
            h = ph / bh
        } else {
            w = w.coerceAtMost(maxW)
            h = h.coerceAtMost(maxH)
        }
        val l = if (sx > 0) ax else ax - w
        val t = if (sy > 0) ay else ay - h
        cropRect.set(l, t, l + w, t + h)
    }

    private fun toNormalized(p: PointF): PointF {
        return PointF(
            (p.x - displayRect.left) / displayRect.width(),
            (p.y - displayRect.top) / displayRect.height()
        )
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

    companion object {
        private const val DIM_COLOR = 0x88000000.toInt()
    }
}
