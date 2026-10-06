package org.ayasequart.sunshower.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.gif.GifReader
import org.ayasequart.sunshower.gif.GifSaver
import org.ayasequart.sunshower.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class EditorActivity : ComponentActivity() {

    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var borderRow: View
    private lateinit var strokeBar: SeekBar
    private val paletteSwatches = ArrayList<Pair<View, GradientDrawable>>()
    private var sourceUri: Uri? = null
    private var frames: List<Bitmap>? = null
    private var isGif = false
    private var selectedColor = PALETTE.first()
    private var savedMessageJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.editor_status)
        borderRow = findViewById(R.id.border_row)
        strokeBar = findViewById(R.id.stroke_bar)
        val buttons = findViewById<View>(R.id.editor_buttons)
        ViewCompat.setOnApplyWindowInsetsListener(buttons) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom + extra)
            WindowInsetsCompat.CONSUMED
        }
        buildPalette()

        findViewById<Button>(R.id.add_rect_button).setOnClickListener {
            overlayView.addOverlay(selectedColor)
        }
        findViewById<Button>(R.id.add_border_button).setOnClickListener {
            overlayView.addOutline(selectedColor)
            borderRow.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.delete_button).setOnClickListener {
            overlayView.deleteSelected()
        }
        strokeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                overlayView.setStrokeWidth(progress.coerceAtLeast(1))
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        findViewById<Button>(R.id.save_button).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { save() }
        }
        load()
    }

    private fun load() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            finish()
            return
        }
        sourceUri = uri
        lifecycleScope.launch(Dispatchers.IO) {
            val gif = isGifFile(uri)
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (decoded == null) {
                withContext(Dispatchers.Main) { statusText.setText(R.string.load_failed) }
                return@launch
            }
            val settings = SettingsStore.load(this@EditorActivity)
            val scale = settings.shortSide.toFloat() / minOf(decoded.width, decoded.height).coerceAtLeast(1)
            val w = (decoded.width * scale).toInt().coerceAtLeast(1)
            val h = (decoded.height * scale).toInt().coerceAtLeast(1)
            val first = Bitmap.createScaledBitmap(decoded, w, h, true)
            withContext(Dispatchers.Main) {
                isGif = gif
                frames = listOf(first)
                overlayView.setBase(first)
            }
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

    private suspend fun save() {
        val settings = SettingsStore.load(this)
        if (isGif) {
            val uri = sourceUri ?: run {
                withContext(Dispatchers.Main) { statusText.setText(R.string.save_failed) }
                return
            }
            val src = try {
                GifReader.read(contentResolver, uri, settings.shortSide)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusText.setText(R.string.save_failed) }
                return
            }
            val dir = File(cacheDir, "edit_frames").also { it.deleteRecursively(); it.mkdirs() }
            src.frames.forEachIndexed { i, f ->
                val target = f.copy(f.config ?: Bitmap.Config.ARGB_8888, true)
                overlayView.drawOverlays(Canvas(target), target.width, target.height)
                File(dir, "frame_%04d.jpg".format(i)).outputStream().use {
                    target.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
            }
            val bytes = GifSaver.encodeFrames(
                this,
                dir,
                src.frames.size,
                src.delaysCentis,
                src.loopCount,
                true
            )
            GifSaver.saveBytes(this, bytes, "${sourceNameBase()}.gif", "image/gif")
            withContext(Dispatchers.Main) { onSaved() }
        } else {
            val f = frames?.first() ?: return
            val target = f.copy(f.config ?: Bitmap.Config.ARGB_8888, true)
            overlayView.drawOverlays(Canvas(target), target.width, target.height)
            val bytes = ByteArrayOutputStream().also {
                target.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            GifSaver.saveBytes(this, bytes, "${sourceNameBase()}.png", "image/png")
            withContext(Dispatchers.Main) { onSaved() }
        }
    }

    private fun sourceNameBase(): String {
        val uri = sourceUri ?: return SettingsStore.DEFAULT_FILE_NAME
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
        val base = name?.let { n -> n.substringBeforeLast('.').trim() }.orEmpty()
        return base.ifEmpty { SettingsStore.DEFAULT_FILE_NAME }
    }

    private fun onSaved() {
        savedMessageJob?.cancel()
        statusText.setText(R.string.saved)
        savedMessageJob = lifecycleScope.launch {
            delay(SAVED_MESSAGE_MS)
            statusText.text = ""
        }
    }

    private fun buildPalette() {
        val row = findViewById<LinearLayout>(R.id.palette_row)
        val density = resources.displayMetrics.density
        for (color in PALETTE) {
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.RECTANGLE
            shape.cornerRadius = 8 * density
            shape.setColor(color)
            val swatch = View(this)
            val lp = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
            lp.marginEnd = (8 * density).toInt()
            swatch.layoutParams = lp
            swatch.background = shape
            swatch.setOnClickListener {
                selectedColor = color
                for ((v, s) in paletteSwatches) {
                    s.setStroke((2 * density).toInt(), android.graphics.Color.TRANSPARENT)
                }
                shape.setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
                overlayView.updateSelectedColor(color)
            }
            paletteSwatches.add(swatch to shape)
            row.addView(swatch)
        }
        paletteSwatches.first().second.setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
    }

    companion object {
        private const val SAVED_MESSAGE_MS = 3000L
        const val EXTRA_URI = "uri"
        private val PALETTE = intArrayOf(
            0xFF212121.toInt(),
            0xFFE53935.toInt(),
            0xFF43A047.toInt(),
            0xFF1E88E5.toInt(),
            0xFFFDD835.toInt(),
            0xFF8E24AA.toInt(),
            0xFFFB8C00.toInt(),
            0xFFFAFAFA.toInt()
        )
    }
}
