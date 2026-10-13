package org.ayasequart.sunshower.video

import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.ui.applySystemBarInsets
import org.ayasequart.sunshower.util.displayName
import org.ayasequart.sunshower.util.formatBytes
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

class VideoActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var fileInfoText: TextView
    private lateinit var extLabel: TextView
    private lateinit var pickButton: Button
    private lateinit var applyButton: Button
    private lateinit var fileNameInput: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var compressSection: View
    private lateinit var resolutionGroup: RadioGroup
    private lateinit var fpsGroup: RadioGroup
    private lateinit var qualityValue: TextView
    private lateinit var qualityBar: SeekBar
    private lateinit var videoFormatSection: View
    private lateinit var videoFormatGroup: RadioGroup
    private lateinit var videoDefaultRadio: RadioButton
    private lateinit var videoAltRadio: RadioButton
    private lateinit var audioQualitySection: View
    private lateinit var audioQualitySwitch: CheckBox
    private lateinit var audioQualityRow: View
    private lateinit var audioQualityValue: TextView
    private lateinit var audioQualityBar: SeekBar
    private lateinit var estimateText: TextView

    private var pickedUri: Uri? = null
    private var info: VideoInfo? = null
    private var savedMessageJob: Job? = null
    private var busy = false

    private var opusAvailable = false
    private var h264Available = false
    private var h265Available = false
    private var vp8Available = false
    private var vp9Available = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            info = null
            onPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            (16 * resources.displayMetrics.density).toInt()
        )

        statusText = findViewById(R.id.video_status)
        fileInfoText = findViewById(R.id.video_file_info)
        extLabel = findViewById(R.id.video_ext_label)
        pickButton = findViewById(R.id.video_pick_button)
        applyButton = findViewById(R.id.video_apply_button)
        fileNameInput = findViewById(R.id.video_file_name_input)
        modeGroup = findViewById(R.id.video_mode_group)
        compressSection = findViewById(R.id.video_compress_section)
        resolutionGroup = findViewById(R.id.video_resolution_group)
        fpsGroup = findViewById(R.id.video_fps_group)
        qualityValue = findViewById(R.id.video_quality_value)
        qualityBar = findViewById(R.id.video_quality_bar)
        videoFormatSection = findViewById(R.id.video_format_section)
        videoFormatGroup = findViewById(R.id.video_format_group)
        videoDefaultRadio = findViewById(R.id.radio_video_fmt_default)
        videoAltRadio = findViewById(R.id.radio_video_fmt_alt)
        audioQualitySection = findViewById(R.id.audio_quality_section)
        audioQualitySwitch = findViewById(R.id.audio_quality_switch)
        audioQualityRow = findViewById(R.id.audio_quality_row)
        audioQualityValue = findViewById(R.id.audio_quality_value)
        audioQualityBar = findViewById(R.id.audio_quality_bar)
        estimateText = findViewById(R.id.video_estimate)

        qualityBar.progress = 4
        qualityValue.text = getString(R.string.video_quality_value, 5)
        qualityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                qualityValue.text = getString(R.string.video_quality_value, progress + 1)
                updateEstimate()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        audioQualityBar.progress = 4
        audioQualityValue.text = getString(R.string.audio_quality_value, defaultAudioKbps())
        audioQualityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                audioQualityValue.text =
                    getString(R.string.audio_quality_value, customAudioKbps())
                updateEstimate()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        audioQualitySwitch.setOnCheckedChangeListener { _, _ ->
            updateModeVisibility()
            updateEstimate()
        }

        modeGroup.setOnCheckedChangeListener { _, _ ->
            updateModeVisibility()
            updateExtLabel()
            updateEstimate()
            updateApplyEnabled()
        }
        resolutionGroup.setOnCheckedChangeListener { _, _ -> onVideoSettingsChanged() }
        fpsGroup.setOnCheckedChangeListener { _, _ -> onVideoSettingsChanged() }
        videoFormatGroup.setOnCheckedChangeListener { _, _ -> onVideoSettingsChanged() }

        pickButton.setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.video_set_unix_button).setOnClickListener {
            fileNameInput.setText((System.currentTimeMillis() / 1000L).toString())
        }
        applyButton.setOnClickListener {
            lifecycleScope.launch { applyToPickedFile() }
        }

        updateModeVisibility()
        updateFormatLabels()
        updateExtLabel()
        updateEstimate()

        lifecycleScope.launch(Dispatchers.IO) {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            fun has(mime: String) = infos.any { it.isEncoder && it.supportedTypes.contains(mime) }
            val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                has(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val h264 = has(MimeTypes.VIDEO_H264)
            val h265 = has(MimeTypes.VIDEO_H265)
            val vp8 = has(MimeTypes.VIDEO_VP8)
            val vp9 = has(MimeTypes.VIDEO_VP9)
            withContext(Dispatchers.Main) {
                opusAvailable = opus
                h264Available = h264
                h265Available = h265
                vp8Available = vp8
                vp9Available = vp9
                updateFormatLabels()
                updateEstimate()
            }
        }
    }

    private fun onPicked(uri: Uri) {
        applyButton.isEnabled = false
        statusText.text = ""
        estimateText.text = ""
        val fullName = uri.displayName(this)
        if (fullName != null) {
            val base = fullName.substringBeforeLast('.').trim()
            if (base.isNotEmpty()) fileNameInput.setText(base)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val probed = VideoInfo.probe(this@VideoActivity, uri)
            withContext(Dispatchers.Main) {
                info = probed
                updateFileInfo()
                updateModeVisibility()
                updateExtLabel()
                updateEstimate()
                updateApplyEnabled()
            }
        }
    }

    private fun updateFileInfo() {
        val i = info ?: return
        val parts = ArrayList<String>()
        val duration = durationText(i.durationUs)
        if (duration != null) parts.add(duration)
        if (i.width > 0 && i.height > 0) parts.add("${i.width}×${i.height}")
        val codecs = ArrayList<String>()
        if (i.videoMime != null) codecs.add(VideoInfo.codecName(i.videoMime))
        if (i.audioMime != null) codecs.add(VideoInfo.codecName(i.audioMime))
        if (codecs.isNotEmpty()) parts.add(codecs.joinToString(" + "))
        if (i.totalBytes > 0) parts.add(formatBytes(i.totalBytes))
        if (parts.isEmpty()) {
            fileInfoText.text = ""
            return
        }
        fileInfoText.text = getString(R.string.video_file_info, parts.joinToString(", "))
    }

    private class CompressChoice(
        val videoMime: String?,
        val containerMime: String,
        val audioMime: String?
    )

    private fun compressChoice(): CompressChoice {
        var videoMime = chosenVideoMime()
        var container = chosenContainer(videoMime)
        val i = info
        if (container == "video/webm" && i != null && i.hasAudio && !opusAvailable) {
            videoMime = MimeTypes.VIDEO_H264
            container = "video/mp4"
        }
        val audioMime = when {
            i == null || !i.hasAudio -> null
            container == "video/webm" -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_AAC
        }
        return CompressChoice(videoMime, container, audioMime)
    }

    private fun removeAudioContainer(): String {
        return if (altSelected()) altContainer() else defaultContainer()
    }

    private fun mode(): Int = when (modeGroup.checkedRadioButtonId) {
        R.id.radio_mode_remove_audio -> MODE_REMOVE_AUDIO
        R.id.radio_mode_extract_audio -> MODE_EXTRACT_AUDIO
        else -> MODE_COMPRESS
    }

    private fun selectedTargetHeight(): Int = when (resolutionGroup.checkedRadioButtonId) {
        R.id.radio_res_320 -> 320
        R.id.radio_res_640 -> 640
        R.id.radio_res_720 -> 720
        else -> 480
    }

    private fun selectedFps(): Int = when (fpsGroup.checkedRadioButtonId) {
        R.id.radio_fps_24 -> 24
        R.id.radio_fps_25 -> 25
        else -> 30
    }

    private fun altSelected(): Boolean =
        videoFormatGroup.checkedRadioButtonId == R.id.radio_video_fmt_alt

    private fun defaultContainer(): String {
        val i = info
        return if (i != null && (i.videoMime == MimeTypes.VIDEO_VP8 ||
                i.videoMime == MimeTypes.VIDEO_VP9)
        ) {
            "video/webm"
        } else {
            "video/mp4"
        }
    }

    private fun altContainer(): String =
        if (defaultContainer() == "video/webm") "video/mp4" else "video/webm"

    private fun sourceAac(): Boolean = info?.audioMime == MimeTypes.AUDIO_AAC

    private fun chosenVideoMime(): String? {
        if (altSelected()) {
            return if (altContainer() == "video/webm") {
                if (vp9Available) MimeTypes.VIDEO_VP9 else MimeTypes.VIDEO_VP8
            } else {
                MimeTypes.VIDEO_H264
            }
        }
        val i = info ?: return null
        val keep = i.videoMime
        val keepable = keep != null && keep in KEEPABLE && encoderAvailable(keep)
        return if (keepable) keep else MimeTypes.VIDEO_H264
    }

    private fun chosenContainer(videoMime: String?): String {
        if (altSelected()) return altContainer()
        return if (videoMime == MimeTypes.VIDEO_VP8 || videoMime == MimeTypes.VIDEO_VP9) {
            "video/webm"
        } else {
            "video/mp4"
        }
    }

    private fun encoderAvailable(mime: String): Boolean = when (mime) {
        MimeTypes.VIDEO_H264 -> h264Available
        MimeTypes.VIDEO_H265 -> h265Available
        MimeTypes.VIDEO_VP8 -> vp8Available
        MimeTypes.VIDEO_VP9 -> vp9Available
        else -> false
    }

    private fun outputDims(i: VideoInfo): Pair<Int, Int> {
        val target = selectedTargetHeight()
        val h = if (i.height > 0) minOf(target, i.height) else target
        if (i.height <= 0 || i.width <= 0) return Pair(0, h)
        val w = (i.width.toDouble() * h / i.height).roundToInt()
        return Pair(w - (w % 2), h)
    }

    private fun effectiveFps(i: VideoInfo): Int {
        val target = selectedFps()
        return if (i.frameRate in 1 until target) i.frameRate else target
    }

    private fun videoBitrateBps(w: Int, h: Int, fps: Int, mime: String?): Int {
        val q = qualityBar.progress + 1
        var bpp = 0.02 + (q - 1) * 0.18 / 9.0
        when (mime) {
            MimeTypes.VIDEO_H265 -> bpp *= 0.75
            MimeTypes.VIDEO_VP9 -> bpp *= 0.72
        }
        return (bpp * w * h * fps).toInt().coerceAtLeast(100_000)
    }

    private fun customAudioKbps(): Int {
        val q = audioQualityBar.progress + 1
        return 48 + (q - 1) * 208 / 9
    }

    private fun defaultAudioKbps(): Int = 128

    private fun chosenAudioBitrateBps(): Int {
        return if (audioQualitySwitch.isChecked) customAudioKbps() * 1000
        else defaultAudioKbps() * 1000
    }

    private fun passthroughSelected(): Boolean {
        val i = info ?: return false
        return mode() == MODE_EXTRACT_AUDIO && sourceAac()
    }

    private fun audioReencodeNeeded(): Boolean {
        val i = info
        if (mode() == MODE_EXTRACT_AUDIO) return !passthroughSelected()
        if (mode() == MODE_COMPRESS) return i?.hasAudio == true
        return false
    }

    private fun onVideoSettingsChanged() {
        updateExtLabel()
        updateEstimate()
    }

    private fun updateModeVisibility() {
        val m = mode()
        compressSection.visibility = if (m == MODE_COMPRESS) View.VISIBLE else View.GONE
        videoFormatSection.visibility =
            if (m == MODE_COMPRESS || m == MODE_REMOVE_AUDIO) View.VISIBLE else View.GONE
        audioQualitySection.visibility = if (audioReencodeNeeded()) View.VISIBLE else View.GONE
        audioQualityRow.visibility =
            if (audioQualitySwitch.isChecked) View.VISIBLE else View.GONE
        updateFormatLabels()
    }

    private fun updateFormatLabels() {
        val i = info
        val defWebm = defaultContainer() == "video/webm"
        val defaultDesc = if (i == null) {
            "—"
        } else {
            "${if (defWebm) "WebM" else "MP4"} · ${VideoInfo.codecName(i.videoMime)}"
        }
        videoDefaultRadio.text = getString(R.string.video_format_default, defaultDesc)
        if (defWebm) {
            videoAltRadio.text = getString(R.string.video_format_mp4)
            videoAltRadio.isEnabled = true
        } else {
            val webmCodec = if (vp9Available) "VP9" else "VP8"
            videoAltRadio.text =
                getString(R.string.video_format_webm, webmCodec) +
                    if (vp8Available || vp9Available) "" else getString(R.string.unavailable_suffix)
            videoAltRadio.isEnabled = vp8Available || vp9Available
            if (!videoAltRadio.isEnabled && altSelected()) {
                videoFormatGroup.check(R.id.radio_video_fmt_default)
            }
        }
    }

    private fun updateExtLabel() {
        extLabel.text = when (mode()) {
            MODE_COMPRESS -> if (compressChoice().containerMime == "video/webm") {
                ".webm"
            } else {
                ".mp4"
            }
            MODE_REMOVE_AUDIO -> if (removeAudioContainer() == "video/webm") ".webm" else ".mp4"
            else -> ".m4a"
        }
    }

    private fun updateEstimate() {
        val i = info
        if (i == null) {
            estimateText.text = ""
            return
        }
        val bytes = when (mode()) {
            MODE_COMPRESS -> {
                val choice = compressChoice()
                val (w, h) = outputDims(i)
                if (w <= 0 || i.durationUs <= 0) {
                    0L
                } else {
                    val fps = effectiveFps(i)
                    val vbps = videoBitrateBps(w, h, fps, choice.videoMime)
                    val abps = if (choice.audioMime != null) {
                        chosenAudioBitrateBps()
                    } else {
                        0
                    }
                    (vbps + abps).toLong() * i.durationUs / 8_000_000L
                }
            }
            MODE_REMOVE_AUDIO -> if (i.videoTrackBytes > 0) i.videoTrackBytes else i.totalBytes
            else -> {
                if (passthroughSelected()) {
                    i.audioTrackBytes
                } else if (i.durationUs > 0) {
                    chosenAudioBitrateBps().toLong() * i.durationUs / 8_000_000L
                } else {
                    0L
                }
            }
        }
        estimateText.text = getString(R.string.video_estimate, formatBytes(bytes))
    }

    private fun updateApplyEnabled() {
        val i = info
        applyButton.isEnabled = !busy && pickedUri != null && i != null &&
            (mode() != MODE_EXTRACT_AUDIO || i.hasAudio)
    }

    private fun applyToPickedFile() = lifecycleScope.launch {
        val uri = pickedUri ?: return@launch
        val i = info ?: return@launch
        setBusy(true)
        savedMessageJob?.cancel()
        statusText.text = ""
        try {
            val dir = File(cacheDir, "video_out")
            dir.mkdirs()
            val output = when (mode()) {
                MODE_COMPRESS -> {
                    val choice = compressChoice()
                    val fileExt = if (choice.containerMime == "video/webm") ".webm" else ".mp4"
                    val outFile = File(dir, "out_${System.currentTimeMillis()}$fileExt")
                    val (w, h) = outputDims(i)
                    val targetHeight = if (i.height > 0) h else selectedTargetHeight()
                    VideoProcessor.runTransformer(
                        this@VideoActivity,
                        uri,
                        outFile,
                        removeAudio = false,
                        targetHeight = targetHeight,
                        targetFps = if (i.frameRate > selectedFps()) selectedFps() else null,
                        videoMime = choice.videoMime,
                        audioMime = choice.audioMime,
                        videoBitrateBps = videoBitrateBps(
                            w, targetHeight, effectiveFps(i), choice.videoMime
                        ),
                        audioBitrateBps = if (choice.audioMime != null) {
                            chosenAudioBitrateBps()
                        } else {
                            null
                        },
                        onProgress = { p -> runOnUiThread { updateProgress(p) } }
                    )
                }
                MODE_REMOVE_AUDIO -> {
                    val keep = i.videoMime
                    val keepable = keep != null && keep in KEEPABLE && encoderAvailable(keep)
                    val container = if (keepable) removeAudioContainer() else "video/mp4"
                    val fileExt = if (container == "video/webm") ".webm" else ".mp4"
                    val outFile = File(dir, "out_${System.currentTimeMillis()}$fileExt")
                    try {
                        VideoProcessor.runTransformer(
                            this@VideoActivity,
                            uri,
                            outFile,
                            removeAudio = true,
                            targetHeight = null,
                            targetFps = null,
                            videoMime = if (keepable) null else MimeTypes.VIDEO_H264,
                            audioMime = null,
                            videoBitrateBps = null,
                            audioBitrateBps = null,
                            onProgress = { p -> runOnUiThread { updateProgress(p) } }
                        )
                    } catch (e: Exception) {
                        outFile.delete()
                        VideoProcessor.runTransformer(
                            this@VideoActivity,
                            uri,
                            outFile,
                            removeAudio = true,
                            targetHeight = null,
                            targetFps = null,
                            videoMime = MimeTypes.VIDEO_H264,
                            audioMime = null,
                            videoBitrateBps = null,
                            audioBitrateBps = null,
                            onProgress = { p -> runOnUiThread { updateProgress(p) } }
                        )
                    }
                }
                else -> {
                    val outFile = File(dir, "out_${System.currentTimeMillis()}")
                    VideoProcessor.extractAudio(
                        this@VideoActivity,
                        uri,
                        outFile,
                        passthrough = passthroughSelected(),
                        bitrateBps = chosenAudioBitrateBps(),
                        durationUs = i.durationUs,
                        onProgress = { p -> runOnUiThread { updateProgress(p) } }
                    )
                }
            }
            val base = fileNameInput.text.toString()
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .ifEmpty { "video" }
            val ext = extForMime(output.mime)
            val isVideo = mode() != MODE_EXTRACT_AUDIO
            val savedUri = withContext(Dispatchers.IO) {
                VideoSaver.saveFile(
                    this@VideoActivity, output.file, "$base$ext", output.mime, isVideo
                )
            }
            output.file.delete()
            if (savedUri == null) throw IOException("save failed")
            onSaved(if (isVideo) "Movies" else "Music")
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            statusText.text =
                getString(R.string.save_failed_reason, e.message ?: e.javaClass.simpleName)
        } finally {
            setBusy(false)
        }
    }

    private fun extForMime(mime: String): String = when (mime) {
        "video/webm", "audio/webm" -> ".webm"
        "audio/mp4" -> ".m4a"
        else -> ".mp4"
    }

    private fun durationText(durationUs: Long): String? {
        if (durationUs <= 0) return null
        val totalSeconds = durationUs / 1_000_000L
        return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
    }

    private fun updateProgress(percent: Int) {
        savedMessageJob?.cancel()
        statusText.text = getString(R.string.video_processing, percent.coerceIn(0, 100))
    }

    private fun setBusy(value: Boolean) {
        busy = value
        pickButton.isEnabled = !value
        updateApplyEnabled()
    }

    private fun onSaved(directory: String) {
        savedMessageJob?.cancel()
        statusText.text = getString(R.string.video_saved_to, directory)
        savedMessageJob = lifecycleScope.launch {
            delay(SAVED_MESSAGE_MS)
            statusText.text = ""
        }
    }

    companion object {
        private const val TAG = "VideoActivity"
        private const val MODE_COMPRESS = 0
        private const val MODE_REMOVE_AUDIO = 1
        private const val MODE_EXTRACT_AUDIO = 2
        private const val SAVED_MESSAGE_MS = 3000L
        private val KEEPABLE = setOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_VP8,
            MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_MP4V
        )
    }
}
