package org.ayasequart.sunshower.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.gif.GifReader
import org.ayasequart.sunshower.gif.GifSaver
import org.ayasequart.sunshower.kuroba.ImageReencoder
import org.ayasequart.sunshower.kuroba.KurobaOptions
import org.ayasequart.sunshower.settings.SettingsStore
import org.ayasequart.sunshower.ui.BusySpinner
import org.ayasequart.sunshower.ui.applySystemBarInsets
import org.ayasequart.sunshower.util.displayName
import org.ayasequart.sunshower.video.VideoInfo
import org.ayasequart.sunshower.video.VideoProcessor
import org.ayasequart.sunshower.video.VideoSaver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

    private var sourceUri: Uri? = null
    private var sourceFormat: Bitmap.CompressFormat? = null
    private var frames: List<Bitmap>? = null
    private var isGif = false
    private var isVideo = false
    private var videoInfo: VideoInfo? = null
    private var cropRect: RectF? = null
    private var imageOptions: ImageOutputOptions? = null
    private var videoOptions: VideoOutputOptions? = null
    private var savedMessageJob: Job? = null
    private var busy = false
    private var hasBorder = false
    private val busySpinner = BusySpinner(this)

    private var h264Available = false
    private var h265Available = false
    private var vp8Available = false
    private var vp9Available = false
    private var opusAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.editor_status)
        borderRow = findViewById(R.id.border_row)
        strokeBar = findViewById(R.id.stroke_bar)
        toolsRow = findViewById(R.id.tools_row)
        actionsRow = findViewById(R.id.actions_row)
        cropBar = findViewById(R.id.crop_bar)
        optionsButton = findViewById(R.id.options_button)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            extraBottomPx = (24 * resources.displayMetrics.density).toInt()
        )
        paletteRow = findViewById(R.id.palette_row)
        palette = PaletteController(this, findViewById(R.id.palette_row)) {
            overlayView.updateSelectedColor(it)
        }
        palette.build()

        findViewById<Button>(R.id.crop_button).setOnClickListener { beginCrop() }
        findViewById<Button>(R.id.add_rect_button).setOnClickListener {
            overlayView.addOverlay(palette.selectedColor)
        }
        findViewById<Button>(R.id.add_border_button).setOnClickListener {
            overlayView.addOutline(palette.selectedColor)
            hasBorder = true
            borderRow.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.delete_button).setOnClickListener {
            overlayView.deleteSelected()
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
        probeEncoders()
    }

    private fun beginCrop() {
        overlayView.beginCrop()
        findViewById<Spinner>(R.id.crop_aspect_spinner).setSelection(0)
        setCropUi(true)
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
        if (isVideo) {
            val info = videoInfo ?: return
            val initial = videoOptions ?: VideoOutputOptions.defaults()
            val sourceWebm = info.videoMime == MimeTypes.VIDEO_VP8 ||
                info.videoMime == MimeTypes.VIDEO_VP9
            OutputOptionsPopup.showVideoOptions(
                this,
                sourceWebm = sourceWebm,
                asisAvailable = if (sourceWebm) {
                    info.videoMime != null && hardwareEncoderAvailable(info.videoMime)
                } else {
                    true
                },
                webmAvailable = vp8Available || vp9Available,
                vp9Available = vp9Available,
                hasAudio = info.hasAudio,
                initial = initial
            ) { videoOptions = it }
        } else if (!isGif) {
            val current = frames?.firstOrNull()
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

    private fun probeEncoders() {
        lifecycleScope.launch(Dispatchers.IO) {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            fun has(mime: String) = infos.any {
                it.isEncoder && it.supportedTypes.contains(mime) && isHardware(it)
            }
            val h264 = has(MimeTypes.VIDEO_H264)
            val h265 = has(MimeTypes.VIDEO_H265)
            val vp8 = has(MimeTypes.VIDEO_VP8)
            val vp9 = has(MimeTypes.VIDEO_VP9)
            val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                has(MimeTypes.AUDIO_OPUS)
            withContext(Dispatchers.Main) {
                h264Available = h264
                h265Available = h265
                vp8Available = vp8
                vp9Available = vp9
                opusAvailable = opus
            }
        }
    }

    private fun isHardware(info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google") || name.startsWith("c2.android") ||
            name.startsWith("c2.google") || name.startsWith("omx.ffmpeg"))
    }

    private fun load() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            finish()
            return
        }
        sourceUri = uri
        statusText.setText(R.string.video_loading)
        lifecycleScope.launch(Dispatchers.IO) {
            when (detectType(uri)) {
                TYPE_VIDEO -> loadVideo(uri)
                else -> loadImage(uri)
            }
        }
    }

    private fun detectType(uri: Uri): Int {
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (mime != null && mime.startsWith("video/")) return TYPE_VIDEO
        if (isGifFile(uri)) return TYPE_GIF
        if (mime != null) return TYPE_IMAGE
        val name = uri.displayName(this)?.lowercase()
        if (name != null && VIDEO_EXTENSIONS.any { name.endsWith(it) }) return TYPE_VIDEO
        return TYPE_IMAGE
    }

    private suspend fun loadVideo(uri: Uri) {
        val info = VideoInfo.probe(this, uri)
        val frame = decodeFirstFrame(uri)
        withContext(Dispatchers.Main) {
            isVideo = true
            videoInfo = info
            optionsButton.visibility = View.VISIBLE
            if (frame == null) {
                statusText.setText(R.string.load_failed)
            } else {
                frames = listOf(frame)
                overlayView.setBase(frame)
                statusText.text = ""
            }
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
        val scale =
            settings.shortSide.toFloat() / minOf(decoded.width, decoded.height).coerceAtLeast(1)
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        val first = Bitmap.createScaledBitmap(decoded, w, h, true)
        withContext(Dispatchers.Main) {
            isGif = gif
            frames = listOf(first)
            overlayView.setBase(first)
            optionsButton.visibility = if (gif) View.GONE else View.VISIBLE
            statusText.text = ""
        }
    }

    private fun decodeFirstFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val frame =
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
            val shortSide = SettingsStore.load(this).shortSide
            val scale =
                shortSide.toFloat() / minOf(frame.width, frame.height).coerceAtLeast(1)
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
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
        if (isVideo) saveVideo() else if (isGif) saveGif() else saveImage()
    }

    private fun saveImage() {
        val base = frames?.first() ?: return
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

    private fun saveVideo() {
        val uri = sourceUri ?: return
        val info = videoInfo
        if (info == null || info.width <= 0 || info.height <= 0) {
            statusText.setText(R.string.load_failed)
            return
        }
        busy = true
        setButtonsEnabled(false)
        statusText.text = ""
        busySpinner.show()
        lifecycleScope.launch {
            try {
                val opts = videoOptions
                val crop = evenCrop(cropRect, info.width, info.height)
                val outW: Int
                val outH: Int
                if (crop != null) {
                    val cw = (crop.width() * info.width).roundToInt()
                    val ch = (crop.height() * info.height).roundToInt()
                    val target = opts?.targetHeight
                    if (target != null && target < ch) {
                        outH = target
                        outW = (cw.toDouble() * target / ch).roundToInt().coerceAtLeast(2)
                    } else {
                        outH = ch
                        outW = cw
                    }
                } else {
                    val target = opts?.targetHeight
                    if (target != null && target < info.height) {
                        outH = target
                        outW = (info.width.toDouble() * target / info.height).roundToInt()
                            .coerceAtLeast(2)
                    } else {
                        outH = info.height
                        outW = info.width
                    }
                }
                val overlay = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                overlayView.drawOverlays(Canvas(overlay), outW, outH)

                var mime = chosenVideoMime(info, opts)
                val isWebm = mime == MimeTypes.VIDEO_VP8 || mime == MimeTypes.VIDEO_VP9
                var containerWebm = isWebm ||
                    (opts?.format == VideoOutputOptions.FORMAT_WEBM && (vp8Available || vp9Available))
                val audioChoice = opts?.audio ?: VideoOutputOptions.AUDIO_KEEP
                val removeAudio = audioChoice == VideoOutputOptions.AUDIO_REMOVE
                var audioMime: String? = null
                var audioBitrate: Int? = null
                if (opts != null && opts.audio == VideoOutputOptions.AUDIO_CUSTOM && info.hasAudio) {
                    if (containerWebm && opusAvailable) {
                        audioMime = MimeTypes.AUDIO_OPUS
                    } else {
                        audioMime = MimeTypes.AUDIO_AAC
                    }
                    audioBitrate = opts.audioKbps * 1000
                }
                if (containerWebm && audioMime == MimeTypes.AUDIO_AAC) {
                    containerWebm = false
                }
                if (!containerWebm && isWebm) {
                    // VP8/VP9 cannot go into MP4: force H.264
                    mime = MimeTypes.VIDEO_H264
                }
                val ext = if (containerWebm) ".webm" else ".mp4"
                val containerMime = if (containerWebm) "video/webm" else "video/mp4"

                val fps = effectiveFps(info, opts?.targetFps)
                val bitrate = if (opts != null) {
                    videoBitrateBps(outW, outH, fps, mime, opts.quality)
                } else {
                    null
                }
                val targetHeight = if (outH != info.height) outH else null

                val dir = File(cacheDir, "video_out").also { it.mkdirs() }
                val outFile = File(dir, "out_${System.currentTimeMillis()}$ext")
                val output = VideoProcessor.runTransformer(
                    this@EditorActivity,
                    uri,
                    outFile,
                    removeAudio = removeAudio,
                    targetHeight = targetHeight,
                    targetFps = opts?.targetFps,
                    videoMime = mime,
                    audioMime = audioMime,
                    videoBitrateBps = bitrate,
                    audioBitrateBps = audioBitrate,
                    cropRect = crop,
                    overlayBitmap = overlay,
                    onProgress = { p -> runOnUiThread { updateProgress(p) } }
                )
                val base = sourceNameBase()
                val savedUri = withContext(Dispatchers.IO) {
                    VideoSaver.saveFile(
                        this@EditorActivity, output.file, "$base$ext", containerMime, true
                    )
                }
                output.file.delete()
                if (savedUri == null) throw IOException("save failed")
                onSaved()
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                statusText.text =
                    getString(R.string.save_failed_reason, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
                setButtonsEnabled(true)
                busySpinner.hide()
            }
        }
    }

    private fun chosenVideoMime(info: VideoInfo, opts: VideoOutputOptions?): String {
        when (opts?.format) {
            VideoOutputOptions.FORMAT_MP4 -> return MimeTypes.VIDEO_H264
            VideoOutputOptions.FORMAT_WEBM -> return if (vp9Available) {
                MimeTypes.VIDEO_VP9
            } else {
                MimeTypes.VIDEO_VP8
            }
        }
        val keep = info.videoMime
        if (keep != null && keep in KEEPABLE && hardwareEncoderAvailable(keep)) return keep
        return MimeTypes.VIDEO_H264
    }

    private fun hardwareEncoderAvailable(mime: String): Boolean = when (mime) {
        MimeTypes.VIDEO_H264 -> h264Available
        MimeTypes.VIDEO_H265 -> h265Available
        MimeTypes.VIDEO_VP8 -> vp8Available
        MimeTypes.VIDEO_VP9 -> vp9Available
        else -> false
    }

    private fun effectiveFps(info: VideoInfo, target: Int?): Int {
        if (target == null) return info.frameRate.coerceAtLeast(1)
        return if (info.frameRate in 1 until target) info.frameRate else target
    }

    private fun videoBitrateBps(w: Int, h: Int, fps: Int, mime: String?, quality: Int): Int {
        var bpp = 0.02 + (quality - 1) * 0.18 / 9.0
        when (mime) {
            MimeTypes.VIDEO_H265 -> bpp *= 0.75
            MimeTypes.VIDEO_VP9 -> bpp *= 0.72
        }
        return (bpp * w * h * fps).toInt().coerceAtLeast(100_000)
    }

    private fun evenCrop(crop: RectF?, w: Int, h: Int): RectF? {
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

    private fun cropBitmap(source: Bitmap, crop: RectF): Bitmap {
        val l = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 2)
        val t = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 2)
        val w = (crop.width() * source.width).roundToInt().coerceIn(2, source.width - l)
        val h = (crop.height() * source.height).roundToInt().coerceIn(2, source.height - t)
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (id in intArrayOf(
            R.id.crop_button,
            R.id.add_rect_button,
            R.id.add_border_button,
            R.id.delete_button,
            R.id.options_button,
            R.id.save_button
        )) {
            findViewById<Button>(id).isEnabled = enabled
        }
    }

    private fun sourceNameBase(): String {
        val uri = sourceUri ?: return SettingsStore.DEFAULT_FILE_NAME
        val name = uri.displayName(this)
        val base = name?.let { n -> n.substringBeforeLast('.').trim() }.orEmpty()
        return base.ifEmpty { SettingsStore.DEFAULT_FILE_NAME }
    }

    private fun updateProgress(percent: Int) {
        savedMessageJob?.cancel()
        statusText.text = getString(R.string.video_processing, percent.coerceIn(0, 100))
    }

    private fun onSaved() {
        savedMessageJob?.cancel()
        statusText.setText(R.string.saved)
        savedMessageJob = lifecycleScope.launch {
            delay(SAVED_MESSAGE_MS)
            statusText.text = ""
        }
    }

    companion object {
        private const val TAG = "EditorActivity"
        private const val SAVED_MESSAGE_MS = 3000L
        private const val TYPE_IMAGE = 0
        private const val TYPE_GIF = 1
        private const val TYPE_VIDEO = 2
        private val KEEPABLE = setOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_VP8,
            MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_MP4V
        )
        private val VIDEO_EXTENSIONS = setOf(
            ".mp4", ".m4v", ".webm", ".mkv", ".avi", ".mov", ".3gp", ".3gpp", ".3g2", ".ts", ".mpg", ".mpeg"
        )
        const val EXTRA_URI = "uri"
    }
}
