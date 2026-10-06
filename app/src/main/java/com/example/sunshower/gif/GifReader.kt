package com.example.sunshower.gif

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import pl.droidsonroids.gif.GifDrawable

class GifSource(
    val frames: List<Bitmap>,
    val width: Int,
    val height: Int,
    val delaysCentis: IntArray,
    val loopCount: Int
)

object GifReader {

    fun read(resolver: ContentResolver, uri: Uri, targetShortSide: Int): GifSource {
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open $uri")
        val drawable = GifDrawable(afd)
        drawable.stop()
        val frameCount = drawable.numberOfFrames
        val srcW = drawable.intrinsicWidth
        val srcH = drawable.intrinsicHeight
        val scale = targetShortSide.toFloat() / minOf(srcW, srcH).coerceAtLeast(1)
        val outW = (srcW * scale).toInt().coerceAtLeast(1)
        val outH = (srcH * scale).toInt().coerceAtLeast(1)
        val delays = IntArray(frameCount) { i -> ((drawable.getFrameDuration(i) + 5) / 10).coerceAtLeast(1) }
        val loopCount = drawable.getLoopCount().coerceIn(0, 65535)
        val frames = ArrayList<Bitmap>(frameCount)
        for (i in 0 until frameCount) {
            val frame = drawable.seekToFrameAndGet(i)
            frames.add(Bitmap.createScaledBitmap(frame, outW, outH, true))
        }
        return GifSource(frames, outW, outH, delays, loopCount)
    }
}
