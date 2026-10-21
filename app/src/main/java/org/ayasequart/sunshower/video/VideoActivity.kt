package org.ayasequart.sunshower.video

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
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
import org.ayasequart.sunshower.edit.CropUtils
import org.ayasequart.sunshower.edit.OverlayView
import org.ayasequart.sunshower.settings.SettingsStore
import org.ayasequart.sunshower.ui.BusySpinner
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
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var qualityValue: TextView
    private lateinit var qualityBar: SeekBar
    private lateinit var videoFormatGroup: RadioGroup
    private lateinit var videoDefaultRadio: RadioButton
    private lateinit var videoAltRadio: RadioButton
    private lateinit var audioRemoveSwitch: CheckBox
    private lateinit var audioQualitySwitch: CheckBox
    private lateinit var audioQualityRow: View
    private lateinit var audioQualityValue: TextView
    private lateinit var audioQualityBar: SeekBar
    private lateinit var audioExtractButton: Button
    private lateinit var audioNoAudioText: TextView
    private lateinit var estimateText: TextView
    private lateinit var videoOverlay: OverlayView
    private lateinit var videoCropButton: Button
    private lateinit var videoCropApply: Button
    private lateinit var cropAspectSpinner: Spinner
    private lateinit var sectionVideo: View
    private lateinit var sectionCrop: View
    private lateinit var sectionAudio: View
    private lateinit var videoTabCrop: Button
    private lateinit var videoTabVideo: Button
    private lateinit var videoTabAudio: Button
    private var section = SECTION_VIDEO

    private var pickedUri: Uri? = null
    private var info: VideoInfo? = null
    private var savedMessageJob: Job? = null
    private var busy = false
    private var cropRect: RectF? = null
    private var decodedFrame: Bitmap? = null
    private val busySpinner = BusySpinner(this)

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
            extraTopPx = (24 * resources.displayMetrics.density).toInt(),
            extraBottomPx = (16 * resources.displayMetrics.density).toInt()
        )

        statusText = findViewById(R.id.video_status)
        fileInfoText = findViewById(R.id.video_file_info)
        extLabel = findViewById(R.id.video_ext_label)
        pickButton = findViewById(R.id.video_pick_button)
        applyButton = findViewById(R.id.video_apply_button)
        fileNameInput = findViewById(R.id.video_file_name_input)
        resolutionSpinner = findViewById(R.id.video_resolution_spinner)
        fpsSpinner = findViewById(R.id.video_fps_spinner)
        qualityValue = findViewById(R.id.video_quality_value)
        qualityBar = findViewById(R.id.video_quality_bar)
        videoFormatGroup = findViewById(R.id.video_format_group)
        videoDefaultRadio = findViewById(R.id.radio_video_fmt_default)
        videoAltRadio = findViewById(R.id.radio_video_fmt_alt)
        audioRemoveSwitch = findViewById(R.id.audio_remove_switch)
        audioQualitySwitch = findViewById(R.id.audio_quality_switch)
        audioQualityRow = findViewById(R.id.audio_quality_row)
        audioQualityValue = findViewById(R.id.audio_quality_value)
        audioQualityBar = findViewById(R.id.audio_quality_bar)
        audioExtractButton = findViewById(R.id.audio_extract_button)
        audioNoAudioText = findViewById(R.id.audio_no_audio)
        estimateText = findViewById(R.id.video_estimate)
        videoOverlay = findViewById(R.id.video_overlay_view)
        videoCropButton = findViewById(R.id.video_crop_button)
        videoCropApply = findViewById(R.id.video_crop_apply)
        cropAspectSpinner = findViewById(R.id.video_crop_aspect_spinner)
        sectionVideo = findViewById(R.id.section_video)
        sectionCrop = findViewById(R.id.section_crop)
        sectionAudio = findViewById(R.id.section_audio)
        videoTabCrop = findViewById(R.id.video_tab_crop)
        videoTabVideo = findViewById(R.id.video_tab_video)
        videoTabAudio = findViewById(R.id.video_tab_audio)
        videoTabCrop.setOnClickListener { selectSection(SECTION_CROP) }
        videoTabVideo.setOnClickListener { selectSection(SECTION_VIDEO) }
        videoTabAudio.setOnClickListener { selectSection(SECTION_AUDIO) }
        setupCropControls()

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

        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.video_res_original),
                getString(R.string.video_res_320),
                getString(R.string.video_res_480),
                getString(R.string.video_res_640),
                getString(R.string.video_res_720)
            )
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        resolutionSpinner.setSelection(0)

        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.video_fps_original),
                getString(R.string.video_fps_24),
                getString(R.string.video_fps_25),
                getString(R.string.video_fps_30)
            )
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        fpsSpinner.setSelection(0)

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

        audioRemoveSwitch.setOnCheckedChangeListener { _, _ ->
            updateAudioUi()
            updateEstimate()
        }

        audioQualitySwitch.setOnCheckedChangeListener { _, _ ->
            updateAudioUi()
            updateEstimate()
        }

        audioExtractButton.setOnClickListener {
            lifecycleScope.launch { exportAudio() }
        }

        val settingsChanged = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                onVideoSettingsChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        resolutionSpinner.onItemSelectedListener = settingsChanged
        fpsSpinner.onItemSelectedListener = settingsChanged
        videoFormatGroup.setOnCheckedChangeListener { _, _ -> onVideoSettingsChanged() }

        pickButton.setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.video_set_unix_button).setOnClickListener {
            fileNameInput.setText((System.currentTimeMillis() / 1000L).toString())
        }
        applyButton.setOnClickListener {
            lifecycleScope.launch { applyToPickedFile() }
        }

        selectSection(SECTION_VIDEO)
        updateAudioUi()
        updateFormatLabels()
        updateExtLabel()
        updateEstimate()
        pickVideo.launch("video/*")

        lifecycleScope.launch(Dispatchers.IO) {
            val mimes = CodecSupport.hardwareEncoderMimes()
            fun has(mime: String) = mimes.contains(mime)
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
                updateAudioUi()
                updateFormatLabels()
                updateEstimate()
            }
        }
    }

    private fun setupCropControls() {
        cropAspectSpinner.adapter = ArrayAdapter(
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
        val aspects = listOf<Float?>(null, 1f, 4f / 3f, 16f / 9f, 9f / 16f)
        cropAspectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (videoOverlay.isCropMode()) {
                    videoOverlay.setCropAspect(aspects[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        videoCropButton.setOnClickListener {
            if (videoOverlay.isCropMode()) {
                videoOverlay.cancelCrop()
                endCropUi()
            } else {
                videoOverlay.beginCrop()
                videoCropButton.setText(R.string.crop_cancel)
                videoCropApply.isEnabled = true
            }
        }
        videoCropApply.setOnClickListener { applyCrop() }
        endCropUi()
    }

    private fun endCropUi() {
        videoCropButton.setText(R.string.crop)
        videoCropApply.isEnabled = false
    }

    private fun applyCrop() {
        val crop = videoOverlay.applyCrop()
        if (crop.width() < 0.999f || crop.height() < 0.999f) {
            cropRect = CropUtils.composeCrop(cropRect, crop)
            val current = decodedFrame
            if (current != null) {
                val cropped = CropUtils.cropBitmap(current, crop)
                decodedFrame = cropped
                videoOverlay.setBase(cropped)
            }
            updateEstimate()
        }
        endCropUi()
    }

    private fun resetCrop() {
        videoOverlay.cancelCrop()
        endCropUi()
        cropRect = null
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
            val frame = decodeFirstFrame(uri)
            withContext(Dispatchers.Main) {
                info = probed
                resetCrop()
                decodedFrame = frame
                if (frame != null) {
                    videoOverlay.setBase(frame)
                }
                updateFileInfo()
                updateAudioUi()
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

    private fun sourceWebm(): Boolean {
        val mime = info?.videoMime
        return mime == MimeTypes.VIDEO_VP8 || mime == MimeTypes.VIDEO_VP9
    }

    private fun asisAvailable(): Boolean {
        val i = info ?: return false
        return if (sourceWebm()) {
            i.videoMime != null && encoderAvailable(i.videoMime)
        } else {
            true
        }
    }

    private fun altAvailable(): Boolean {
        return if (sourceWebm()) true else vp8Available || vp9Available
    }

    private fun compressChoice(): CompressChoice {
        val i = info ?: return CompressChoice(MimeTypes.VIDEO_H264, "video/mp4", null)
        val useAlt = altSelected() && altAvailable()
        val videoMime: String
        val container: String
        when {
            useAlt && sourceWebm() -> {
                videoMime = MimeTypes.VIDEO_H264
                container = "video/mp4"
            }
            useAlt -> {
                videoMime = if (vp9Available) MimeTypes.VIDEO_VP9 else MimeTypes.VIDEO_VP8
                container = "video/webm"
            }
            asisAvailable() -> {
                videoMime = i.videoMime ?: MimeTypes.VIDEO_H264
                container = if (sourceWebm()) "video/webm" else "video/mp4"
            }
            else -> {
                videoMime = MimeTypes.VIDEO_H264
                container = "video/mp4"
            }
        }
        val removeAudio = audioRemoveSwitch.isChecked
        val audioMime = when {
            removeAudio || !i.hasAudio -> null
            container == "video/webm" -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_AAC
        }
        if (container == "video/webm" && !removeAudio && i.hasAudio && !opusAvailable) {
            val fallbackMime =
                if (videoMime == MimeTypes.VIDEO_VP8 || videoMime == MimeTypes.VIDEO_VP9) {
                    MimeTypes.VIDEO_H264
                } else {
                    videoMime
                }
            return CompressChoice(fallbackMime, "video/mp4", MimeTypes.AUDIO_AAC)
        }
        return CompressChoice(videoMime, container, audioMime)
    }

    private fun selectedTargetHeight(): Int? =
        when (resolutionSpinner.selectedItemPosition) {
            1 -> 320
            2 -> 480
            3 -> 640
            4 -> 720
            else -> null
        }

    private fun selectedFps(): Int? = when (fpsSpinner.selectedItemPosition) {
        1 -> 24
        2 -> 25
        3 -> 30
        else -> null
    }

    private fun altSelected(): Boolean =
        videoFormatGroup.checkedRadioButtonId == R.id.radio_video_fmt_alt

    private fun encoderAvailable(mime: String): Boolean = when (mime) {
        MimeTypes.VIDEO_H264 -> h264Available
        MimeTypes.VIDEO_H265 -> h265Available
        MimeTypes.VIDEO_VP8 -> vp8Available
        MimeTypes.VIDEO_VP9 -> vp9Available
        else -> false
    }

    private fun outputDims(i: VideoInfo): Pair<Int, Int> {
        val crop = cropRect
        val baseW: Int
        val baseH: Int
        if (crop != null && i.width > 0 && i.height > 0) {
            baseW = (crop.width() * i.width).roundToInt().coerceAtLeast(2)
            baseH = (crop.height() * i.height).roundToInt().coerceAtLeast(2)
        } else {
            baseW = i.width.coerceAtLeast(0)
            baseH = i.height.coerceAtLeast(0)
        }
        val target = selectedTargetHeight()
        if (target == null) {
            return Pair(baseW - (baseW % 2), baseH - (baseH % 2))
        }
        if (baseH <= 0 || baseW <= 0) return Pair(0, target)
        val h = minOf(target, baseH)
        val w = (baseW.toDouble() * h / baseH).roundToInt()
        return Pair(w - (w % 2), h)
    }

    private fun effectiveFps(i: VideoInfo): Int {
        val target = selectedFps()
        if (target == null) return i.frameRate.coerceAtLeast(1)
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

    private fun sourceAac(): Boolean = info?.audioMime == MimeTypes.AUDIO_AAC

    private fun selectSection(target: Int) {
        section = target
        sectionVideo.visibility = if (target == SECTION_VIDEO) View.VISIBLE else View.GONE
        sectionCrop.visibility = if (target == SECTION_CROP) View.VISIBLE else View.GONE
        sectionAudio.visibility = if (target == SECTION_AUDIO) View.VISIBLE else View.GONE
        videoTabCrop.alpha = if (target == SECTION_CROP) 1f else 0.4f
        videoTabVideo.alpha = if (target == SECTION_VIDEO) 1f else 0.4f
        videoTabAudio.alpha = if (target == SECTION_AUDIO) 1f else 0.4f
    }

    private fun onVideoSettingsChanged() {
        updateExtLabel()
        updateEstimate()
    }

    private fun updateAudioUi() {
        val hasAudio = info?.hasAudio == true
        audioRemoveSwitch.isEnabled = hasAudio
        audioExtractButton.isEnabled = hasAudio && !busy
        audioNoAudioText.visibility = if (hasAudio) View.GONE else View.VISIBLE
        audioQualitySwitch.isEnabled = hasAudio && !audioRemoveSwitch.isChecked
        audioQualityRow.visibility =
            if (hasAudio && !audioRemoveSwitch.isChecked && audioQualitySwitch.isChecked) {
                View.VISIBLE
            } else {
                View.GONE
            }
        updateFormatLabels()
    }

    private fun updateFormatLabels() {
        val i = info
        if (i == null) {
            videoDefaultRadio.text = "—"
            videoAltRadio.text = "—"
            return
        }
        videoDefaultRadio.text =
            getString(R.string.video_fmt_asis, if (sourceWebm()) "WebM" else "MP4")
        if (sourceWebm()) {
            videoAltRadio.text = getString(R.string.video_format_mp4)
        } else {
            val webmCodec = if (vp9Available) "VP9" else "VP8"
            videoAltRadio.text = getString(R.string.video_format_webm, webmCodec)
        }
        videoDefaultRadio.visibility = if (asisAvailable()) View.VISIBLE else View.GONE
        videoAltRadio.visibility = if (altAvailable()) View.VISIBLE else View.GONE
        val checked = videoFormatGroup.checkedRadioButtonId
        val checkedHidden =
            (checked == R.id.radio_video_fmt_default && !asisAvailable()) ||
                (checked == R.id.radio_video_fmt_alt && !altAvailable())
        if (checkedHidden || checked == -1) {
            videoFormatGroup.check(
                if (asisAvailable()) R.id.radio_video_fmt_default else R.id.radio_video_fmt_alt
            )
        }
    }

    private fun updateExtLabel() {
        extLabel.text = if (compressChoice().containerMime == "video/webm") {
            ".webm"
        } else {
            ".mp4"
        }
    }

    private fun updateEstimate() {
        val i = info
        if (i == null) {
            estimateText.text = ""
            return
        }
        val choice = compressChoice()
        val (w, h) = outputDims(i)
        val bytes = if (w <= 0 || i.durationUs <= 0) {
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
        estimateText.text = getString(R.string.video_estimate, formatBytes(bytes))
    }

    private fun updateApplyEnabled() {
        applyButton.isEnabled = !busy && pickedUri != null && info != null
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
            val choice = compressChoice()
            val fileExt = if (choice.containerMime == "video/webm") ".webm" else ".mp4"
            val outFile = File(dir, "out_${System.currentTimeMillis()}$fileExt")
            val (w, h) = outputDims(i)
            val targetHeight = selectedTargetHeight()?.let { h }
            val output = VideoProcessor.runTransformer(
                this@VideoActivity,
                uri,
                outFile,
                removeAudio = audioRemoveSwitch.isChecked,
                targetHeight = targetHeight,
                targetFps = selectedFps()?.takeIf { i.frameRate > it },
                videoMime = choice.videoMime,
                audioMime = choice.audioMime,
                videoBitrateBps = videoBitrateBps(w, h, effectiveFps(i), choice.videoMime),
                audioBitrateBps = if (choice.audioMime != null) {
                    chosenAudioBitrateBps()
                } else {
                    null
                },
                cropRect = CropUtils.evenCrop(cropRect, i.width, i.height),
                onProgress = { p -> runOnUiThread { updateProgress(p) } }
            )
            val base = fileNameInput.text.toString()
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .ifEmpty { "video" }
            val ext = extForMime(output.mime)
            val savedUri = withContext(Dispatchers.IO) {
                VideoSaver.saveFile(this@VideoActivity, output.file, "$base$ext", output.mime, true)
            }
            output.file.delete()
            if (savedUri == null) throw IOException("save failed")
            onSaved("Movies")
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            statusText.text =
                getString(R.string.save_failed_reason, e.message ?: e.javaClass.simpleName)
        } finally {
            setBusy(false)
        }
    }

    private suspend fun exportAudio() {
        val uri = pickedUri ?: return
        val i = info ?: return
        if (!i.hasAudio) return
        setBusy(true)
        savedMessageJob?.cancel()
        statusText.text = ""
        try {
            val dir = File(cacheDir, "video_out")
            dir.mkdirs()
            val outFile = File(dir, "out_${System.currentTimeMillis()}")
            val output = VideoProcessor.extractAudio(
                this@VideoActivity,
                uri,
                outFile,
                passthrough = sourceAac(),
                bitrateBps = chosenAudioBitrateBps(),
                durationUs = i.durationUs,
                onProgress = { p -> runOnUiThread { updateProgress(p) } }
            )
            val base = fileNameInput.text.toString()
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .ifEmpty { "audio" }
            val ext = extForMime(output.mime)
            val savedUri = withContext(Dispatchers.IO) {
                VideoSaver.saveFile(this@VideoActivity, output.file, "$base$ext", output.mime, false)
            }
            output.file.delete()
            if (savedUri == null) throw IOException("save failed")
            onSaved("Music")
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
        if (value) busySpinner.show() else busySpinner.hide()
        updateApplyEnabled()
        updateAudioUi()
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
        private const val SECTION_CROP = 0
        private const val SECTION_VIDEO = 1
        private const val SECTION_AUDIO = 2
        private const val SAVED_MESSAGE_MS = 3000L
    }
}
