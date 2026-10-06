package org.ayasequart.sunshower.gif

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder

class GifSource(
    val frames: List<Bitmap>,
    val width: Int,
    val height: Int,
    val delaysCentis: IntArray,
    val loopCount: Int
)

object GifReader {

    private val bitmapProvider = object : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) {}

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)

        override fun release(bytes: ByteArray) {}

        override fun obtainIntArray(size: Int): IntArray = IntArray(size)

        override fun release(array: IntArray) {}
    }

    fun read(resolver: ContentResolver, uri: Uri, targetShortSide: Int): GifSource {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot open $uri")
        val decoder = StandardGifDecoder(bitmapProvider)
        val status = decoder.read(bytes)
        if (status != GifDecoder.STATUS_OK && status != GifDecoder.STATUS_PARTIAL_DECODE) {
            throw IllegalArgumentException("Cannot decode GIF $uri (status $status)")
        }
        val frameCount = decoder.frameCount
        if (frameCount <= 0) {
            decoder.clear()
            throw IllegalArgumentException("GIF has no frames: $uri")
        }
        val srcW = decoder.width
        val srcH = decoder.height
        val scale = targetShortSide.toFloat() / minOf(srcW, srcH).coerceAtLeast(1)
        val outW = (srcW * scale).toInt().coerceAtLeast(1)
        val outH = (srcH * scale).toInt().coerceAtLeast(1)
        val delays = IntArray(frameCount) { i -> ((decoder.getDelay(i) + 5) / 10).coerceAtLeast(1) }
        // NETSCAPE_LOOP_COUNT_DOES_NOT_EXIST (-1) maps to 0, matching the old library where
        // 0 means "loop forever".
        val loopCount = decoder.netscapeLoopCount.coerceAtLeast(0).coerceAtMost(65535)
        val frames = ArrayList<Bitmap>(frameCount)
        for (i in 0 until frameCount) {
            decoder.advance()
            val frame = decoder.nextFrame
                ?: throw IllegalArgumentException("Cannot decode frame $i of $uri")
            frames.add(Bitmap.createScaledBitmap(frame, outW, outH, true))
        }
        decoder.clear()
        return GifSource(frames, outW, outH, delays, loopCount)
    }
}
