package org.ayasequart.sunshower.kuroba

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

object ImageReencoder {

    private const val PIXEL_DIFF = 5
    private const val MAX_REDUCE_PERCENT = 95

    private val PNG_HEADER = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val JPEG_HEADER = byteArrayOf(-1, -40)
    private val WEBP_HEADER = arrayOf(
        byteArrayOf(0x52, 0x49, 0x46, 0x46),
        byteArrayOf(0x57, 0x45, 0x42, 0x50)
    )

    private val random = Random()

    fun getImageFormat(file: File): Bitmap.CompressFormat? {
        return try {
            FileInputStream(file).use { getImageFormat(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun getImageFormat(stream: InputStream): Bitmap.CompressFormat? {
        return try {
            val header = ByteArray(16)
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            when {
                isPngHeader(header) -> Bitmap.CompressFormat.PNG
                isJpegHeader(header) -> Bitmap.CompressFormat.JPEG
                isWebpHeader(header) -> Bitmap.CompressFormat.WEBP
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun exifRotationDegrees(resolver: ContentResolver, uri: Uri): Float {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
                rotationFromExif(orientation)
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    fun reencode(source: Bitmap, options: KurobaOptions, rotateDegrees: Float): Bitmap {
        if (!options.changeChecksum && options.reducePercent <= 0 && rotateDegrees == 0f) {
            return source
        }

        var source = source
        if (options.changeChecksum && !source.isMutable) {
            source = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        }

        if (options.changeChecksum) {
            changeBitmapChecksum(source)
        }

        val matrix = Matrix()
        if (options.reducePercent > 0) {
            val scale = (100f - options.reducePercent.coerceAtMost(MAX_REDUCE_PERCENT)) / 100f
            matrix.setScale(scale, scale)
        }
        if (rotateDegrees != 0f) {
            matrix.postRotate(rotateDegrees)
        }

        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun rotationFromExif(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        else -> 0f
    }

    private fun changeBitmapChecksum(bitmap: Bitmap) {
        val randomX = abs(random.nextInt()) % bitmap.width
        val randomY = abs(random.nextInt()) % bitmap.height

        var pixel = bitmap.getPixel(randomX, randomY)

        if (pixel - PIXEL_DIFF >= 0) {
            pixel -= PIXEL_DIFF
        } else {
            pixel += PIXEL_DIFF
        }

        bitmap.setPixel(randomX, randomY, pixel)
    }

    private fun isWebpHeader(header: ByteArray): Boolean {
        if (!header.sliceArray(0..3).contentEquals(WEBP_HEADER[0])) {
            return false
        }
        return header.sliceArray(8..11).contentEquals(WEBP_HEADER[1])
    }

    private fun isJpegHeader(header: ByteArray): Boolean {
        val size = min(JPEG_HEADER.size, header.size)
        for (i in 0 until size) {
            if (header[i] != JPEG_HEADER[i]) {
                return false
            }
        }
        return true
    }

    private fun isPngHeader(header: ByteArray): Boolean {
        val size = min(PNG_HEADER.size, header.size)
        for (i in 0 until size) {
            if (header[i] != PNG_HEADER[i]) {
                return false
            }
        }
        return true
    }
}
