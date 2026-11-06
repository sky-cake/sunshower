package org.ayasequart.sunshower.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.ayasequart.sunshower.util.durationText
import kotlin.math.ceil

class TrimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onTrimChanged(startFraction: Float, endFraction: Float, activeFraction: Float)
        fun onTrimSettled(activeFraction: Float) {}
        fun onThumbnailsNeeded(times: LongArray) {}
    }

    var listener: Listener? = null

    private val barRect = RectF()
    private val barPath = Path()
    private val dimPaint = Paint()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val thumbDst = RectF()
    private val handleWidth = resources.displayMetrics.density * 10f
    private val handleHeight = resources.displayMetrics.density * 26f
    private val handleCorner = resources.displayMetrics.density * 3f
    private val fineScrubStartPx = resources.displayMetrics.density * 24f

    private var dragHandle = -1
    private var startFraction = 0f
    private var endFraction = 1f
    private var hasVideo = false
    private var durationUs = 0L
    private var frameTimesUs = LongArray(0)

    private var dragStartY = 0f
    private var lastTouchX = 0f
    private var virtualX = 0f

    private val thumbnailCache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>): Boolean {
            return size > MAX_THUMBNAILS
        }
    }
    private val requestedTimes = HashSet<Long>()

    fun setDuration(durationUs: Long) {
        this.durationUs = durationUs
        hasVideo = durationUs > 0
        startFraction = 0f
        endFraction = 1f
        clearThumbnails()
        invalidate()
        requestThumbnails()
    }

    fun setFrameTimesUs(times: LongArray) {
        frameTimesUs = times
        requestedTimes.clear()
        invalidate()
    }

    fun setThumbnail(timeUs: Long, bitmap: Bitmap) {
        thumbnailCache[timeUs] = bitmap
        invalidate()
    }

    fun clearThumbnails() {
        thumbnailCache.clear()
        requestedTimes.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBar()
        if (hasVideo) {
            requestThumbnails()
        }
    }

    private fun layoutBar() {
        val density = resources.displayMetrics.density
        val sidePadding = 24 * density
        val verticalMargin = 12 * density
        val barHeight = 24 * density
        val y = height - verticalMargin - barHeight
        barRect.set(sidePadding, y, width - sidePadding, y + barHeight)
        barPath.reset()
        barPath.addRoundRect(barRect, 4 * density, 4 * density, Path.Direction.CW)
        labelPaint.textSize = 11 * density
        labelPaint.color = LABEL_COLOR
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasVideo || barRect.width() <= 0f) return
        val density = resources.displayMetrics.density

        barPaint.color = BAR_COLOR
        canvas.drawRoundRect(barRect, 4 * density, 4 * density, barPaint)

        val save = canvas.save()
        canvas.clipPath(barPath)
        drawThumbnails(canvas)
        drawTicks(canvas)

        val selLeft = barLeft(startFraction)
        val selRight = barLeft(endFraction)
        selectedPaint.color = SELECTED_COLOR
        canvas.drawRect(selLeft, barRect.top, selRight, barRect.bottom, selectedPaint)

        dimPaint.color = DIM_COLOR
        canvas.drawRect(barRect.left, barRect.top, selLeft, barRect.bottom, dimPaint)
        canvas.drawRect(selRight, barRect.top, barRect.right, barRect.bottom, dimPaint)
        canvas.restoreToCount(save)

        drawEdgeLabels(canvas)
        drawHandle(canvas, selLeft)
        drawHandle(canvas, selRight)
    }

    private fun drawThumbnails(canvas: Canvas) {
        if (thumbnailCache.isEmpty() || barRect.height() <= 0f) return
        val slotW = barRect.height()
        val count = ceil(barRect.width() / slotW).toInt()
        for (i in 0 until count) {
            val time = slotTimeUs(i, count) ?: continue
            val bmp = thumbnailCache[time] ?: continue
            val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            val h = barRect.height()
            val w = (h * aspect).coerceIn(h * 0.2f, h * 3f)
            thumbDst.set(
                barRect.left + i * slotW + (slotW - w) / 2f,
                barRect.top,
                barRect.left + i * slotW + (slotW + w) / 2f,
                barRect.bottom
            )
            canvas.drawBitmap(bmp, null, thumbDst, thumbPaint)
        }
    }

    private fun drawTicks(canvas: Canvas) {
        val times = frameTimesUs
        val dur = durationUs
        if (times.isEmpty() || dur <= 0L || times.size < 2) return
        val pxPerUs = barRect.width() / dur
        if ((times[times.size - 1] - times[0]) * pxPerUs / (times.size - 1) < 4f) {
            return
        }
        tickPaint.color = TICK_COLOR
        tickPaint.strokeWidth = resources.displayMetrics.density
        for (t in times) {
            val x = barLeft(t.toFloat() / dur)
            canvas.drawLine(x, barRect.top, x, barRect.bottom, tickPaint)
        }
    }

    private fun drawEdgeLabels(canvas: Canvas) {
        if (durationUs <= 0L) return
        val fm = labelPaint.fontMetrics
        val y = barRect.top - 4 * resources.displayMetrics.density - fm.descent
        labelPaint.textAlign = Paint.Align.LEFT
        val startText = durationText(0L) ?: "0:00"
        canvas.drawText(startText, barRect.left, y, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        val endText = durationText(durationUs) ?: "0:00"
        canvas.drawText(endText, barRect.right, y, labelPaint)
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
                dragStartY = event.y
                lastTouchX = x
                virtualX = x
                val dStart = Math.abs(x - barLeft(startFraction))
                val dEnd = Math.abs(x - barLeft(endFraction))
                dragHandle = if (dStart <= dEnd) HANDLE_START else HANDLE_END
                updateTrim(virtualX)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragHandle != -1) {
                    val dy = dragStartY - event.y
                    val speed = if (dy <= fineScrubStartPx) {
                        1f
                    } else {
                        (fineScrubStartPx / dy).coerceAtLeast(MIN_SCRUB_SPEED)
                    }
                    virtualX += (x - lastTouchX) * speed
                    lastTouchX = x
                    updateTrim(virtualX)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasDraggingHandle = dragHandle != -1
                val active = if (dragHandle == HANDLE_START) startFraction else endFraction
                dragHandle = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasDraggingHandle) {
                    listener?.onTrimSettled(active)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragHandle = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateTrim(x: Float) {
        val fraction = snapToFrame(xToFraction(x))
        when (dragHandle) {
            HANDLE_START -> startFraction = fraction
                .coerceIn(0f, (endFraction - minRange()).coerceAtLeast(0f))
            HANDLE_END -> endFraction = fraction
                .coerceIn((startFraction + minRange()).coerceAtMost(1f), 1f)
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

    private fun snapToFrame(fraction: Float): Float {
        val dur = durationUs
        if (fraction <= 0f || fraction >= 1f || dur <= 0L) return fraction
        val snapped = snapUs((fraction * dur).toLong())
        if (snapped < 0L) return fraction
        return (snapped.toFloat() / dur).coerceIn(0f, 1f)
    }

    private fun snapUs(targetUs: Long): Long {
        val times = frameTimesUs
        if (times.isEmpty()) return targetUs
        val idx = times.binarySearch(targetUs)
        if (idx >= 0) return times[idx]
        val insertion = -(idx + 1)
        val before = if (insertion > 0) times[insertion - 1] else Long.MIN_VALUE
        val after = if (insertion < times.size) times[insertion] else Long.MAX_VALUE
        val nearest = if (targetUs - before <= after - targetUs) before else after
        return if (nearest == Long.MIN_VALUE || nearest == Long.MAX_VALUE) targetUs else nearest
    }

    private fun minRange(): Float {
        val times = frameTimesUs
        val dur = durationUs
        var range = MIN_RANGE
        if (times.size >= 2 && dur > 0L) {
            val step = (times[times.size - 1] - times[times.size - 2]).toFloat()
            range = maxOf(range, (step / dur).coerceIn(0f, MIN_RANGE))
        }
        val barW = barRect.width()
        if (barW > 0f) {
            range = maxOf(range, handleWidth / barW)
        }
        return range
    }

    private fun requestThumbnails() {
        val times = neededThumbnailTimes()
        if (times.isNotEmpty()) {
            listener?.onThumbnailsNeeded(times)
        }
    }

    private fun neededThumbnailTimes(): LongArray {
        if (durationUs <= 0L || barRect.width() <= 0f || barRect.height() <= 0f) {
            return LongArray(0)
        }
        val slotW = barRect.height()
        val count = ceil(barRect.width() / slotW).toInt()
        val out = ArrayList<Long>()
        for (i in 0 until count) {
            val time = slotTimeUs(i, count) ?: continue
            if (time !in thumbnailCache && requestedTimes.add(time)) {
                out.add(time)
            }
        }
        return out.toLongArray()
    }

    private fun slotTimeUs(slot: Int, count: Int): Long? {
        if (count <= 0 || durationUs <= 0L) return null
        return snapUs(((slot + 0.5f) / count * durationUs).toLong())
    }

    companion object {
        private const val HANDLE_START = 0
        private const val HANDLE_END = 1
        private const val MIN_RANGE = 0.02f
        private const val MIN_SCRUB_SPEED = 0.05f
        private const val MAX_THUMBNAILS = 240
        private const val BAR_COLOR = 0xFF444444.toInt()
        private const val SELECTED_COLOR = 0x803E5876.toInt()
        private const val DIM_COLOR = 0x66000000
        private const val TICK_COLOR = 0x40FFFFFF
        private const val LABEL_COLOR = 0xFFCCCCCC.toInt()
    }
}
