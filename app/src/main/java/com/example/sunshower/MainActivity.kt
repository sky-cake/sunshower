package com.example.sunshower

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
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
import com.example.sunshower.gif.GifSaver
import com.example.sunshower.settings.SettingsStore
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
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var recording = false
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
        statusText = findViewById(R.id.status_text)
        recordButton.setOnClickListener { toggleRecording() }
        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom + extra)
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
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
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
        val rotated = if (rotation == 0) {
            bitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
            recordButton.setText(R.string.record)
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
                    }
                }
            }
        } else {
            frameDir = File(cacheDir, "capture_frames").also { it.deleteRecursively(); it.mkdirs() }
            frameIndex = 0
            lastFrameMs = 0L
            recording = true
            recordButton.setText(R.string.stop)
            statusText.setText(R.string.recording)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
