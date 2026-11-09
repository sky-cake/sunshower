package org.ayasequart.sunshower.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.gif.GifReader
import org.ayasequart.sunshower.gif.GifSaver
import org.ayasequart.sunshower.kuroba.ImageReencoder
import org.ayasequart.sunshower.kuroba.KurobaOptions
import org.ayasequart.sunshower.settings.SettingsStore
import org.ayasequart.sunshower.ui.BusySpinner
import org.ayasequart.sunshower.ui.StatusMessage
import org.ayasequart.sunshower.ui.applySystemBarInsets
import org.ayasequart.sunshower.util.displayName
import org.ayasequart.sunshower.util.uriBaseName
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

class EditorActivity : ComponentActivity() {

    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var borderRow: View
    private lateinit var strokeBar: SeekBar
    private lateinit var palette: PaletteController
    private lateinit var paletteRow: View
    private lateinit var toolsRow: View
    private lateinit var actionsRow: View
    private lateinit var cropBar: View
    private lateinit var optionsButton: Button
    private lateinit var drawButton: Button

    private var sourceUri: Uri? = null
    private var sourceFormat: Bitmap.CompressFormat? = null
    private var frames: List<Bitmap>? = null
    private var fullFrame: Bitmap? = null
    private var isGif = false
    private var cropRect: RectF? = null
    private var imageOptions: ImageOutputOptions? = null
    private lateinit var savedMessage: StatusMessage
    private var busy = false
    private var hasBorder = false
    private val busySpinner = BusySpinner(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.editor_status)
        savedMessage = StatusMessage(lifecycleScope, statusText)
        borderRow = findViewById(R.id.border_row)
        strokeBar = findViewById(R.id.stroke_bar)
        toolsRow = findViewById(R.id.tools_row)
        actionsRow = findViewById(R.id.actions_row)
        cropBar = findViewById(R.id.crop_bar)
        optionsButton = findViewById(R.id.options_button)
        drawButton = findViewById(R.id.draw_button)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            extraBottomPx = (24 * resources.displayMetrics.density).toInt()
        )
        paletteRow = findViewById(R.id.palette_row)
        palette = PaletteController(this, findViewById(R.id.palette_row)) {
            overlayView.updateSelectedColor(it)
            overlayView.setDrawColor(it)
        }
        palette.build()

        findViewById<Button>(R.id.crop_button).setOnClickListener { beginCrop() }
        findViewById<Button>(R.id.draw_button).setOnClickListener { toggleDraw() }
        findViewById<Button>(R.id.add_rect_button).setOnClickListener {
            exitDrawMode()
            overlayView.addOverlay(palette.selectedColor)
        }
        findViewById<Button>(R.id.add_border_button).setOnClickListener {
            exitDrawMode()
            overlayView.addOutline(palette.selectedColor)
            hasBorder = true
            borderRow.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.delete_button).setOnClickListener {
            if (overlayView.isDrawMode()) {
                overlayView.undoLastStroke()
            } else {
                overlayView.deleteSelected()
            }
        }
        optionsButton.setOnClickListener { showOptions() }
        findViewById<Button>(R.id.save_button).setOnClickListener {
            if (!busy) save()
        }
        strokeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                overlayView.setStrokeWidth(progress.coerceAtLeast(1))
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        findViewById<Button>(R.id.crop_cancel_button).setOnClickListener {
            overlayView.cancelCrop()
            setCropUi(false)
        }
        findViewById<Button>(R.id.crop_apply_button).setOnClickListener { applyCrop() }
        val aspectSpinner = findViewById<Spinner>(R.id.crop_aspect_spinner)
        val aspects = listOf<Float?>(
            null, 1f, 4f / 3f, 16f / 9f, 9f / 16f
        )
        aspectSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.crop_aspect_free),
                getString(R.string.crop_aspect_1_1),
                getString(R.string.crop_aspect_4_3),
                getString(R.string.crop_aspect_16_9),
                getString(R.string.crop_aspect_9_16)
            )
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        aspectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (overlayView.isCropMode()) {
                    overlayView.setCropAspect(aspects[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        load()
    }

    private fun beginCrop() {
        exitDrawMode()
        overlayView.beginCrop()
        findViewById<Spinner>(R.id.crop_aspect_spinner).setSelection(0)
        setCropUi(true)
    }

    private fun toggleDraw() {
        if (overlayView.isDrawMode()) {
            exitDrawMode()
        } else {
            overlayView.setDrawMode(true)
            overlayView.setDrawColor(palette.selectedColor)
            drawButton.setText(R.string.done)
            borderRow.visibility = View.VISIBLE
        }
    }

    private fun exitDrawMode() {
        if (overlayView.isDrawMode()) {
            overlayView.setDrawMode(false)
            drawButton.setText(R.string.draw)
            borderRow.visibility = if (hasBorder) View.VISIBLE else View.GONE
        }
    }

    private fun setCropUi(cropping: Boolean) {
        paletteRow.visibility = if (cropping) View.GONE else View.VISIBLE
        toolsRow.visibility = if (cropping) View.GONE else View.VISIBLE
        actionsRow.visibility = if (cropping) View.GONE else View.VISIBLE
        borderRow.visibility =
            if (cropping || !hasBorder) View.GONE else View.VISIBLE
        cropBar.visibility = if (cropping) View.VISIBLE else View.GONE
    }

    private fun applyCrop() {
        val crop = overlayView.applyCrop()
        if (crop.width() < 0.999f || crop.height() < 0.999f) {
            cropRect = composeCrop(cropRect, crop)
            val current = frames?.firstOrNull()
            if (current != null) {
                val l = (crop.left * current.width).roundToInt().coerceIn(0, current.width - 2)
                val t = (crop.top * current.height).roundToInt().coerceIn(0, current.height - 2)
                val w = (crop.width() * current.width).roundToInt()
                    .coerceIn(2, current.width - l)
                val h = (crop.height() * current.height).roundToInt()
                    .coerceIn(2, current.height - t)
                frames = listOf(Bitmap.createBitmap(current, l, t, w, h))
                overlayView.setBase(frames!!.first())
            }
            val full = fullFrame
            if (full != null) {
                val l = (crop.left * full.width).roundToInt().coerceIn(0, full.width - 2)
                val t = (crop.top * full.height).roundToInt().coerceIn(0, full.height - 2)
                val w = (crop.width() * full.width).roundToInt()
                    .coerceIn(2, full.width - l)
                val h = (crop.height() * full.height).roundToInt()
                    .coerceIn(2, full.height - t)
                fullFrame = Bitmap.createBitmap(full, l, t, w, h)
            }
        }
        setCropUi(false)
    }

    private fun composeCrop(prev: RectF?, crop: RectF): RectF {
        if (prev == null) return RectF(crop)
        return RectF(
            prev.left + crop.left * prev.width(),
            prev.top + crop.top * prev.height(),
            prev.right - (1f - crop.right) * prev.width(),
            prev.bottom - (1f - crop.bottom) * prev.height()
        )
    }

    private fun showOptions() {
        if (!isGif) {
            val current = fullFrame ?: frames?.firstOrNull()
            val initial = imageOptions ?: ImageOutputOptions.defaults()
            OutputOptionsPopup.showImageOptions(
                this,
                sourceFormat,
                current?.width ?: 0,
                current?.height ?: 0,
                initial
            ) { imageOptions = it }
        }
    }

    private fun load() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            finish()
            return
        }
        sourceUri = uri
        statusText.setText(R.string.loading)
        lifecycleScope.launch(Dispatchers.IO) {
            loadImage(uri)
        }
    }

    private suspend fun loadImage(uri: Uri) {
        val gif = isGifFile(uri)
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (decoded == null) {
            withContext(Dispatchers.Main) { statusText.setText(R.string.load_failed) }
            return
        }
        sourceFormat = contentResolver.openInputStream(uri)?.use {
            ImageReencoder.getImageFormat(it)
        }
        val settings = SettingsStore.load(this)
        val previewShortSide = maxOf(settings.shortSide, 720)
        val scale =
            previewShortSide.toFloat() / minOf(decoded.width, decoded.height).coerceAtLeast(1)
        val preview = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }
        withContext(Dispatchers.Main) {
            isGif = gif
            fullFrame = decoded
            frames = listOf(preview)
            overlayView.setBase(preview)
            optionsButton.visibility = if (gif) View.GONE else View.VISIBLE
            statusText.text = ""
        }
    }

    private fun isGifFile(uri: Uri): Boolean {
        if (contentResolver.getType(uri) == "image/gif") return true
        if (uri.toString().endsWith(".gif", ignoreCase = true)) return true
        return contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(6)
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) return@use false
                read += n
            }
            val magic = String(header, Charsets.US_ASCII)
            magic == "GIF87a" || magic == "GIF89a"
        } ?: false
    }

    private fun save() {
        if (isGif) saveGif() else saveImage()
    }

    private fun saveImage() {
        val base = fullFrame ?: frames?.first() ?: return
        busy = true
        optionsButton.isEnabled = false
        busySpinner.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val opts = imageOptions
                val target = base.copy(base.config ?: Bitmap.Config.ARGB_8888, true)
                overlayView.drawOverlays(Canvas(target), target.width, target.height)
                var out = target
                if (opts != null && opts.reducePercent > 0) {
                    out = ImageReencoder.reencode(
                        out,
                        KurobaOptions(
                            fileName = "",
                            changeChecksum = false,
                            fixExif = false,
                            removeMetadata = false,
                            reencodeType = opts.format,
                            jpegQuality = opts.jpegQuality,
                            reducePercent = opts.reducePercent
                        ),
                        0f
                    )
                }
                val format = when (opts?.format) {
                    ImageOutputOptions.AS_JPEG -> Bitmap.CompressFormat.JPEG
                    ImageOutputOptions.AS_PNG -> Bitmap.CompressFormat.PNG
                    else -> sourceFormat ?: Bitmap.CompressFormat.PNG
                }
                val isJpeg = format == Bitmap.CompressFormat.JPEG
                val ext = when (format) {
                    Bitmap.CompressFormat.JPEG -> "jpg"
                    Bitmap.CompressFormat.WEBP -> "webp"
                    else -> "png"
                }
                val mime = when (format) {
                    Bitmap.CompressFormat.JPEG -> "image/jpeg"
                    Bitmap.CompressFormat.WEBP -> "image/webp"
                    else -> "image/png"
                }
                val quality = if (isJpeg) (opts?.jpegQuality ?: 100) else 100
                val bytes = ByteArrayOutputStream().also {
                    out.compress(format, quality, it)
                }.toByteArray()
                GifSaver.saveBytes(this@EditorActivity, bytes, "${sourceNameBase()}.$ext", mime)
                withContext(Dispatchers.Main) { onSaved() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text =
                        getString(R.string.save_failed_reason, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                busy = false
                optionsButton.isEnabled = true
                busySpinner.hide()
            }
        }
    }

    private fun saveGif() {
        val uri = sourceUri ?: return
        busy = true
        busySpinner.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = SettingsStore.load(this@EditorActivity)
                val src = GifReader.read(contentResolver, uri, settings.shortSide)
                val dir = File(cacheDir, "edit_frames").also { it.deleteRecursively(); it.mkdirs() }
                val crop = cropRect
                src.frames.forEachIndexed { i, f ->
                    var target = f.copy(f.config ?: Bitmap.Config.ARGB_8888, true)
                    if (crop != null) {
                        target = cropBitmap(target, crop)
                    }
                    overlayView.drawOverlays(Canvas(target), target.width, target.height)
                    File(dir, "frame_%04d.jpg".format(i)).outputStream().use {
                        target.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                }
                val bytes = GifSaver.encodeFrames(
                    this@EditorActivity,
                    dir,
                    src.frames.size,
                    src.delaysCentis,
                    src.loopCount,
                    true
                )
                GifSaver.saveBytes(this@EditorActivity, bytes, "${sourceNameBase()}.gif", "image/gif")
                withContext(Dispatchers.Main) { onSaved() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text =
                        getString(R.string.save_failed_reason, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                busy = false
                busySpinner.hide()
            }
        }
    }

    private fun cropBitmap(source: Bitmap, crop: RectF): Bitmap {
        val l = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 2)
        val t = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 2)
        val w = (crop.width() * source.width).roundToInt().coerceIn(2, source.width - l)
        val h = (crop.height() * source.height).roundToInt().coerceIn(2, source.height - t)
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    private fun sourceNameBase(): String {
        val uri = sourceUri ?: return SettingsStore.DEFAULT_FILE_NAME
        return uriBaseName(uri.displayName(this)) ?: SettingsStore.DEFAULT_FILE_NAME
    }

    private fun onSaved() {
        savedMessage.show(getString(R.string.saved))
    }

    companion object {
        const val EXTRA_URI = "uri"
    }
}
