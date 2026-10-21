package org.ayasequart.sunshower.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
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
import org.ayasequart.sunshower.edit.OverlayView
import org.ayasequart.sunshower.edit.PaletteController
import org.ayasequart.sunshower.settings.SettingsStore
import org.ayasequart.sunshower.ui.applySystemBarInsets
import org.ayasequart.sunshower.util.displayName
import java.io.File
import java.io.IOException

class VideoOverlayActivity : ComponentActivity() {

    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var borderRow: View
    private lateinit var strokeBar: SeekBar
    private lateinit var palette: PaletteController

    private var sourceUri: Uri? = null
    private var videoInfo: VideoInfo? = null
    private var savedMessageJob: Job? = null
    private var busy = false

    private var h264Available = false
    private var h265Available = false
    private var vp8Available = false
    private var vp9Available = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_overlay)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.status_text)
        borderRow = findViewById(R.id.border_row)
        strokeBar = findViewById(R.id.stroke_bar)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            extraBottomPx = (24 * resources.displayMetrics.density).toInt()
        )
        palette = PaletteController(this, findViewById(R.id.palette_row)) {
            overlayView.updateSelectedColor(it)
        }
        palette.build()

        findViewById<Button>(R.id.add_rect_button).setOnClickListener {
            overlayView.addOverlay(palette.selectedColor)
        }
        findViewById<Button>(R.id.add_border_button).setOnClickListener {
            overlayView.addOutline(palette.selectedColor)
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
        findViewById<Button>(R.id.save_button).setOnClickListener { save() }
        probeEncoders()
        load()
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
            withContext(Dispatchers.Main) {
                h264Available = h264
                h265Available = h265
                vp8Available = vp8
                vp9Available = vp9
            }
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
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
            val info = VideoInfo.probe(this@VideoOverlayActivity, uri)
            val frame = decodeFirstFrame(uri)
            withContext(Dispatchers.Main) {
                videoInfo = info
                if (frame == null) {
                    statusText.setText(R.string.load_failed)
                } else {
                    overlayView.setBase(frame)
                    statusText.text = ""
                }
            }
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

    private fun chosenVideoMime(info: VideoInfo): String {
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

    private fun save() {
        if (busy) return
        val uri = sourceUri ?: return
        val info = videoInfo
        if (info == null || info.width <= 0 || info.height <= 0) {
            statusText.setText(R.string.load_failed)
            return
        }
        busy = true
        setButtonsEnabled(false)
        savedMessageJob?.cancel()
        statusText.text = ""
        lifecycleScope.launch {
            try {
                val overlay = Bitmap.createBitmap(
                    info.width, info.height, Bitmap.Config.ARGB_8888
                )
                overlayView.drawOverlays(Canvas(overlay), overlay.width, overlay.height)
                val mime = chosenVideoMime(info)
                val isWebm = mime == MimeTypes.VIDEO_VP8 || mime == MimeTypes.VIDEO_VP9
                val ext = if (isWebm) ".webm" else ".mp4"
                val dir = File(cacheDir, "video_out").also { it.mkdirs() }
                val outFile = File(dir, "out_${System.currentTimeMillis()}$ext")
                val output = VideoProcessor.runTransformer(
                    this@VideoOverlayActivity,
                    uri,
                    outFile,
                    removeAudio = false,
                    targetHeight = null,
                    targetFps = null,
                    videoMime = mime,
                    audioMime = null,
                    videoBitrateBps = null,
                    audioBitrateBps = null,
                    overlayBitmap = overlay,
                    onProgress = { p -> runOnUiThread { updateProgress(p) } }
                )
                val base = sourceNameBase()
                val savedUri = withContext(Dispatchers.IO) {
                    VideoSaver.saveFile(
                        this@VideoOverlayActivity, output.file, "$base$ext", output.mime, true
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
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (id in intArrayOf(
            R.id.add_rect_button, R.id.add_border_button, R.id.delete_button, R.id.save_button
        )) {
            findViewById<Button>(id).isEnabled = enabled
        }
    }

    private fun sourceNameBase(): String {
        val uri = sourceUri ?: return DEFAULT_FILE_NAME
        val name = uri.displayName(this)
        val base = name?.let { n -> n.substringBeforeLast('.').trim() }.orEmpty()
        return base.ifEmpty { DEFAULT_FILE_NAME }
    }

    private fun updateProgress(percent: Int) {
        savedMessageJob?.cancel()
        statusText.text = getString(R.string.video_processing, percent.coerceIn(0, 100))
    }

    private fun onSaved() {
        savedMessageJob?.cancel()
        statusText.setText(R.string.video_saved_to_movies)
        savedMessageJob = lifecycleScope.launch {
            delay(SAVED_MESSAGE_MS)
            statusText.text = ""
        }
    }

    companion object {
        private const val TAG = "VideoOverlayActivity"
        private const val SAVED_MESSAGE_MS = 3000L
        private const val DEFAULT_FILE_NAME = "video"
        const val EXTRA_URI = "uri"
        private val KEEPABLE = setOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_VP8,
            MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_MP4V
        )
    }
}
