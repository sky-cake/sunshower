package com.example.sunshower.gif

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.Image
import com.squareup.gifencoder.ImageOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object GifSaver {

    private val RED_LEVELS = uniformLevels(8)
    private val GREEN_LEVELS = uniformLevels(8)
    private val BLUE_LEVELS = uniformLevels(4)

    fun saveBytes(context: Context, bytes: ByteArray, displayName: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, availableName(context.contentResolver, displayName))
            values.put(MediaStore.Images.Media.MIME_TYPE, mime)
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sunshower")
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val stream = context.contentResolver.openOutputStream(uri) ?: return null
            stream.use { it.write(bytes) }
            return uri
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
        val file = uniqueFile(dir, displayName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    private fun availableName(resolver: ContentResolver, name: String): String {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val used = HashSet<String>()
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    used.add(cursor.getString(idx))
                }
            }
        } catch (e: Exception) {
            return name
        }
        if (name !in used) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while ("$base ($n)$ext" in used) {
            n++
        }
        return "$base ($n)$ext"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    fun encodeFrames(
        context: Context,
        framesDir: File,
        frameCount: Int,
        fps: Int,
        loopMode: Int,
        loopCount: Int,
        dithering: Boolean
    ): ByteArray {
        return encodeFrames(
            context,
            framesDir,
            frameCount,
            schedule(fps, frameCount),
            loopValue(loopMode, loopCount),
            dithering
        )
    }

    fun encodeFrames(
        context: Context,
        framesDir: File,
        frameCount: Int,
        delaysCentis: IntArray,
        loopCount: Int,
        dithering: Boolean
    ): ByteArray {
        val first = BitmapFactory.decodeFile(framePath(framesDir, 0))
            ?: throw IllegalArgumentException("missing frame 0")
        val buffer = IntArray(first.width * first.height)
        readAndQuantize(first, buffer, dithering)
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, first.width, first.height, loopCount)
        val options = ImageOptions()
        for (i in 0 until frameCount) {
            if (i > 0) {
                val frame = BitmapFactory.decodeFile(framePath(framesDir, i))
                    ?: throw IllegalArgumentException("missing frame $i")
                readAndQuantize(frame, buffer, dithering)
            }
            options.setDelay(delaysCentis[i].coerceAtLeast(1) * 10L, TimeUnit.MILLISECONDS)
            encoder.addImage(Image.fromRgb(buffer, first.width), options)
        }
        encoder.finishEncoding()
        return out.toByteArray()
    }

    private fun readAndQuantize(frame: Bitmap, buffer: IntArray, dithering: Boolean) {
        frame.getPixels(buffer, 0, frame.width, 0, 0, frame.width, frame.height)
        if (!dithering) {
            for (i in buffer.indices) {
                val c = buffer[i]
                val r = ((c ushr 16) and 0xFF)
                val g = ((c ushr 8) and 0xFF)
                val b = (c and 0xFF)
                val lr = ((r * 7 + 127) / 255).coerceIn(0, 7)
                val lg = ((g * 7 + 127) / 255).coerceIn(0, 7)
                val lb = ((b * 3 + 127) / 255).coerceIn(0, 3)
                buffer[i] =
                    (RED_LEVELS[lr] shl 16) or (GREEN_LEVELS[lg] shl 8) or BLUE_LEVELS[lb]
            }
            return
        }
        val width = frame.width
        val height = frame.height
        val errR = FloatArray(width + 2)
        val errG = FloatArray(width + 2)
        val errB = FloatArray(width + 2)
        val nextR = FloatArray(width + 2)
        val nextG = FloatArray(width + 2)
        val nextB = FloatArray(width + 2)
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                val c = buffer[rowStart + x]
                val vr = (((c ushr 16) and 0xFF) + errR[x + 1]).toInt().coerceIn(0, 255)
                val vg = (((c ushr 8) and 0xFF) + errG[x + 1]).toInt().coerceIn(0, 255)
                val vb = ((c and 0xFF) + errB[x + 1]).toInt().coerceIn(0, 255)
                val lr = ((vr * 7 + 127) / 255).coerceIn(0, 7)
                val lg = ((vg * 7 + 127) / 255).coerceIn(0, 7)
                val lb = ((vb * 3 + 127) / 255).coerceIn(0, 3)
                buffer[rowStart + x] =
                    (RED_LEVELS[lr] shl 16) or (GREEN_LEVELS[lg] shl 8) or BLUE_LEVELS[lb]
                val dr = vr - RED_LEVELS[lr]
                val dg = vg - GREEN_LEVELS[lg]
                val db = vb - BLUE_LEVELS[lb]
                errR[x + 2] += dr * 0.4375f
                errG[x + 2] += dg * 0.4375f
                errB[x + 2] += db * 0.4375f
                nextR[x] += dr * 0.1875f
                nextG[x] += dg * 0.1875f
                nextB[x] += db * 0.1875f
                nextR[x + 1] += dr * 0.3125f
                nextG[x + 1] += dg * 0.3125f
                nextB[x + 1] += db * 0.3125f
                nextR[x + 2] += dr * 0.0625f
                nextG[x + 2] += dg * 0.0625f
                nextB[x + 2] += db * 0.0625f
            }
            System.arraycopy(nextR, 0, errR, 0, width + 2)
            System.arraycopy(nextG, 0, errG, 0, width + 2)
            System.arraycopy(nextB, 0, errB, 0, width + 2)
            nextR.fill(0f)
            nextG.fill(0f)
            nextB.fill(0f)
        }
    }

    private fun uniformLevels(count: Int): IntArray {
        val levels = IntArray(count)
        for (i in 0 until count) {
            levels[i] = (i * 255 + (count - 1) / 2) / (count - 1)
        }
        return levels
    }

    private fun framePath(framesDir: File, index: Int): String =
        File(framesDir, "frame_%04d.jpg".format(index)).absolutePath

    private fun loopValue(loopMode: Int, loopCount: Int): Int = when (loopMode) {
        0 -> 1
        2 -> loopCount.coerceIn(1, 100)
        else -> 0
    }

    private fun schedule(fps: Int, frameCount: Int): IntArray {
        val delays = IntArray(frameCount)
        var prev = 0
        for (i in 0 until frameCount) {
            val t = ((i + 1) * 100.0 / fps).roundToInt()
            delays[i] = (t - prev).coerceAtLeast(1)
            prev = t
        }
        return delays
    }
}
