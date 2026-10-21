package org.ayasequart.sunshower.edit

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.roundToInt

object CropUtils {

    fun composeCrop(prev: RectF?, crop: RectF): RectF {
        if (prev == null) return RectF(crop)
        return RectF(
            prev.left + crop.left * prev.width(),
            prev.top + crop.top * prev.height(),
            prev.right - (1f - crop.right) * prev.width(),
            prev.bottom - (1f - crop.bottom) * prev.height()
        )
    }

    fun cropBitmap(source: Bitmap, crop: RectF): Bitmap {
        val l = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 2)
        val t = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 2)
        val w = (crop.width() * source.width).roundToInt().coerceIn(2, source.width - l)
        val h = (crop.height() * source.height).roundToInt().coerceIn(2, source.height - t)
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    fun evenCrop(crop: RectF?, w: Int, h: Int): RectF? {
        if (crop == null) return null
        val l = (crop.left * w).roundToInt().coerceIn(0, w - 2)
        val t = (crop.top * h).roundToInt().coerceIn(0, h - 2)
        var r = (crop.right * w).roundToInt().coerceIn(l + 2, w)
        var b = (crop.bottom * h).roundToInt().coerceIn(t + 2, h)
        if ((r - l) % 2 != 0) r--
        if ((b - t) % 2 != 0) b--
        if (r <= l + 1 || b <= t + 1) return null
        return RectF(l.toFloat() / w, t.toFloat() / h, r.toFloat() / w, b.toFloat() / h)
    }
}
