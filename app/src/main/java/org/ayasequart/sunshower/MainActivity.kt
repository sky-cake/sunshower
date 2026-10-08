package org.ayasequart.sunshower

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import org.ayasequart.sunshower.gif.GifSaver
import org.ayasequart.sunshower.settings.SettingsActivity
import org.ayasequart.sunshower.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var recordButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var thumbnailView: ImageView
    private lateinit var statusText: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var recording = false
    private var latestGifUri: Uri? = null
    private var useFrontCamera = false
    private var lastFrameMs = 0L
    private var frameIndex = 0
    private var frameDir: File? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview_view)
        recordButton = findViewById(R.id.record_button)
        switchCameraButton = findViewById(R.id.switch_camera_button)
        settingsButton = findViewById(R.id.settings_button)
        thumbnailView = findViewById(R.id.thumbnail_view)
        statusText = findViewById(R.id.status_text)
        recordButton.setOnClickListener { toggleRecording() }
        switchCameraButton.setOnClickListener { toggleCamera() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        thumbnailView.setOnClickListener { openLatestGif() }
        updateThumbnail()
        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom + extra)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(thumbnailView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = (24 * resources.displayMetrics.density).toInt() + bars.bottom
            view.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { analyzeFrame(it) })
            val selector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            cameraProvider.unbindAll()
            try {
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: IllegalArgumentException) {
                useFrontCamera = !useFrontCamera
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openLatestGif() {
        if (recording) return
        val uri = latestGifUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/gif")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateThumbnail(uri: Uri? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val target = uri ?: latestGifUriOrNull()
            val bitmap = target?.let { decodeThumbnail(it) }
            withContext(Dispatchers.Main) {
                latestGifUri = target
                if (bitmap != null) {
                    thumbnailView.setImageBitmap(bitmap)
                    thumbnailView.visibility = View.VISIBLE
                } else {
                    thumbnailView.visibility = View.GONE
                }
            }
        }
    }

    private fun latestGifUriOrNull(): Uri? {
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.MIME_TYPE}=?",
                arrayOf("image/gif"),
                "${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeThumbnail(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) {
                sample *= 2
            }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toggleCamera() {
        if (recording) {
            Toast.makeText(this, R.string.stop, Toast.LENGTH_SHORT).show()
            return
        }
        useFrontCamera = !useFrontCamera
        startCamera()
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (!recording) {
            image.close()
            return
        }
        val now = System.currentTimeMillis()
        val interval = 1000 / SettingsStore.load(this).fps
        if (now - lastFrameMs < interval) {
            image.close()
            return
        }
        lastFrameMs = now
        val dir = frameDir
        if (dir == null) {
            image.close()
            return
        }
        val bitmap = decodeFrame(image)
        image.close()
        val file = File(dir, "frame_%04d.jpg".format(frameIndex++))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        runOnUiThread { statusText.text = getString(R.string.frames_captured, frameIndex) }
    }

    private fun decodeFrame(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        buffer.rewind()
        val bitmap = if (rowStride == width * 4) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { it.copyPixelsFromBuffer(buffer) }
        } else if (rowStride % 4 == 0 && buffer.remaining() >= rowStride * height) {
            val padded = Bitmap.createBitmap(rowStride / 4, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(padded, 0, 0, width, height)
        } else {
            val pixels = IntArray(width * height)
            val row = ByteArray(width * 4)
            val rowBuffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
            for (r in 0 until height) {
                buffer.position(r * rowStride)
                buffer.get(row, 0, width * 4)
                rowBuffer.rewind()
                for (c in 0 until width) {
                    pixels[r * width + c] = rowBuffer.int
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        val rotation = image.imageInfo.rotationDegrees
        var rotated = if (rotation == 0) {
            bitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        if (useFrontCamera) {
            val matrix = Matrix().apply {
                postScale(-1f, 1f, rotated.width / 2f, rotated.height / 2f)
            }
            rotated = Bitmap.createBitmap(rotated, 0, 0, rotated.width, rotated.height, matrix, true)
        }
        return scaleFrame(rotated)
    }

    private fun scaleFrame(source: Bitmap): Bitmap {
        val settings = SettingsStore.load(this)
        val targetAspect = SettingsStore.aspectRatio(settings.aspect)
        val srcAspect = source.width.toFloat() / source.height
        val cropW: Int
        val cropH: Int
        if (srcAspect > targetAspect) {
            cropH = source.height
            cropW = (cropH * targetAspect).roundToInt().coerceIn(1, source.width)
        } else {
            cropW = source.width
            cropH = (cropW / targetAspect).roundToInt().coerceIn(1, source.height)
        }
        val scale = settings.shortSide.toFloat() / minOf(cropW, cropH).coerceAtLeast(1)
        val outW = (cropW * scale).roundToInt().coerceAtLeast(1)
        val outH = (cropH * scale).roundToInt().coerceAtLeast(1)
        val matrix = Matrix().apply {
            postTranslate(
                -(source.width - cropW) / 2f,
                -(source.height - cropH) / 2f
            )
            postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        )
        return out
    }

    private fun toggleRecording() {
        if (recording) {
            recording = false
            recordButton.setBackgroundResource(R.drawable.shutter)
            recordButton.contentDescription = getString(R.string.record)
            val dir = frameDir
            val count = frameIndex
            if (dir == null || count == 0) {
                statusText.setText(R.string.no_frames)
                return
            }
            statusText.setText(R.string.encoding)
            lifecycleScope.launch(Dispatchers.IO) {
                val settings = SettingsStore.load(this@MainActivity)
                val bytes = GifSaver.encodeFrames(
                    this@MainActivity,
                    dir,
                    count,
                    settings.fps,
                    settings.loopMode,
                    settings.loopCount,
                    settings.dithering
                )
                val uri = GifSaver.saveBytes(this@MainActivity, bytes, "${settings.fileName}.gif", "image/gif")
                withContext(Dispatchers.Main) {
                    if (uri == null) {
                        statusText.setText(R.string.save_failed)
                    } else {
                        statusText.setText(R.string.saved)
                        statusText.postDelayed({
                            if (statusText.text == getString(R.string.saved)) {
                                statusText.text = ""
                            }
                        }, 2000)
                        updateThumbnail(uri)
                    }
                }
            }
        } else {
            frameDir = File(cacheDir, "capture_frames").also { it.deleteRecursively(); it.mkdirs() }
            frameIndex = 0
            lastFrameMs = 0L
            recording = true
            recordButton.setBackgroundResource(R.drawable.shutter_recording)
            recordButton.contentDescription = getString(R.string.stop)
            statusText.setText(R.string.recording)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
