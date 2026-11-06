package org.ayasequart.sunshower.audio

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.ui.BusySpinner
import org.ayasequart.sunshower.ui.StatusMessage
import org.ayasequart.sunshower.ui.applySystemBarInsets
import org.ayasequart.sunshower.util.durationText
import org.ayasequart.sunshower.util.displayName
import org.ayasequart.sunshower.util.extForMime
import org.ayasequart.sunshower.util.formatBytes
import org.ayasequart.sunshower.util.sanitizedFileName
import org.ayasequart.sunshower.util.uriBaseName
import org.ayasequart.sunshower.video.TrimOverlayView
import org.ayasequart.sunshower.video.VideoInfo
import org.ayasequart.sunshower.video.VideoProcessor
import org.ayasequart.sunshower.video.VideoSaver
import java.io.File
import java.io.IOException

class AudioActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var fileInfoText: TextView
    private lateinit var extLabel: TextView
    private lateinit var applyButton: Button
    private lateinit var fileNameInput: EditText
    private lateinit var timeText: TextView
    private lateinit var noAudioText: TextView
    private lateinit var trimOverlay: TrimOverlayView
    private lateinit var savedMessage: StatusMessage

    private var pickedUri: Uri? = null
    private var info: VideoInfo? = null
    private var busy = false
    private var trimStartMs = 0L
    private var trimEndMs = -1L
    private val busySpinner = BusySpinner(this)

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            info = null
            onPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            extraTopPx = (24 * resources.displayMetrics.density).toInt(),
            extraBottomPx = (16 * resources.displayMetrics.density).toInt()
        )

        statusText = findViewById(R.id.audio_status)
        fileInfoText = findViewById(R.id.audio_file_info)
        extLabel = findViewById(R.id.audio_ext_label)
        applyButton = findViewById(R.id.audio_apply_button)
        fileNameInput = findViewById(R.id.audio_file_name_input)
        timeText = findViewById(R.id.audio_time_text)
        noAudioText = findViewById(R.id.audio_no_audio)
        trimOverlay = findViewById(R.id.audio_trim_overlay)
        savedMessage = StatusMessage(lifecycleScope, statusText)
        extLabel.text = ".m4a"

        trimOverlay.listener = object : TrimOverlayView.Listener {
            override fun onTrimChanged(
                startFraction: Float,
                endFraction: Float,
                activeFraction: Float
            ) {
                val i = info ?: return
                trimStartMs = (i.durationUs / 1000 * startFraction).toLong()
                trimEndMs = (i.durationUs / 1000 * endFraction).toLong()
                updateTrimTimeText()
            }
        }

        findViewById<Button>(R.id.audio_set_unix_button).setOnClickListener {
            fileNameInput.setText((System.currentTimeMillis() / 1000L).toString())
        }
        findViewById<Button>(R.id.audio_pick_button).setOnClickListener {
            pickAudio.launch("audio/*")
        }
        applyButton.setOnClickListener {
            lifecycleScope.launch { applyToPickedFile() }
        }

        updateApplyEnabled()
        pickAudio.launch("audio/*")
    }

    private fun onPicked(uri: Uri) {
        applyButton.isEnabled = false
        savedMessage.clear()
        trimStartMs = 0L
        trimEndMs = -1L
        uriBaseName(uri.displayName(this))?.let {
            fileNameInput.setText(it)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val probed = VideoInfo.probe(this@AudioActivity, uri)
            withContext(Dispatchers.Main) {
                info = probed
                trimOverlay.setDuration(probed.durationUs)
                updateFileInfo()
                updateTrimTimeText()
                updateApplyEnabled()
            }
        }
    }

    private fun updateFileInfo() {
        val i = info ?: return
        val parts = ArrayList<String>()
        val duration = durationText(i.durationUs)
        if (duration != null) parts.add(duration)
        if (i.audioMime != null) parts.add(VideoInfo.codecName(i.audioMime))
        if (i.sampleRate > 0) parts.add("${i.sampleRate} Hz")
        if (i.totalBytes > 0) parts.add(formatBytes(i.totalBytes))
        if (parts.isEmpty()) {
            fileInfoText.text = ""
            return
        }
        fileInfoText.text = getString(R.string.file_info, parts.joinToString(", "))
    }

    private fun updateTrimTimeText() {
        val i = info ?: return
        val total = durationText(i.durationUs) ?: "0:00"
        val start = durationText(trimStartMs * 1000) ?: "0:00"
        val end = if (trimEndMs >= 0) durationText(trimEndMs * 1000) ?: total else total
        timeText.text = getString(R.string.trim_range, start, end, total)
    }

    private fun updateApplyEnabled() {
        applyButton.isEnabled = !busy && pickedUri != null && info?.hasAudio == true
        noAudioText.visibility =
            if (info != null && info?.hasAudio == false) View.VISIBLE else View.GONE
        trimOverlay.visibility = if (info?.hasAudio == true) View.VISIBLE else View.GONE
    }

    private fun applyToPickedFile() = lifecycleScope.launch {
        val uri = pickedUri ?: return@launch
        val i = info ?: return@launch
        if (!i.hasAudio) return@launch
        setBusy(true)
        savedMessage.clear()
        try {
            val dir = File(cacheDir, "audio_out")
            dir.mkdirs()
            val outFile = File(dir, "out_${System.currentTimeMillis()}")
            val output = VideoProcessor.extractAudio(
                this@AudioActivity,
                uri,
                outFile,
                passthrough = i.audioMime == MimeTypes.AUDIO_AAC,
                bitrateBps = DEFAULT_BITRATE_BPS,
                durationUs = i.durationUs,
                trimStartUs = trimStartMs * 1000,
                trimEndUs = if (trimEndMs < 0) -1L else trimEndMs * 1000,
                onProgress = { p -> runOnUiThread { updateProgress(p) } }
            )
            val base = fileNameInput.text.toString().sanitizedFileName("audio")
            val ext = extForMime(output.mime)
            val savedUri = withContext(Dispatchers.IO) {
                VideoSaver.saveFile(this@AudioActivity, output.file, "$base$ext", output.mime, false)
            }
            output.file.delete()
            if (savedUri == null) throw IOException("save failed")
            onSaved()
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            statusText.text =
                getString(R.string.save_failed_reason, e.message ?: e.javaClass.simpleName)
        } finally {
            setBusy(false)
        }
    }

    private fun updateProgress(percent: Int) {
        savedMessage.cancel()
        statusText.text = getString(R.string.processing_percent, percent.coerceIn(0, 100))
    }

    private fun setBusy(value: Boolean) {
        busy = value
        applyButton.isEnabled = !value && pickedUri != null && info?.hasAudio == true
        if (value) busySpinner.show() else busySpinner.hide()
    }

    private fun onSaved() {
        savedMessage.show(getString(R.string.video_saved_to, "Music"))
    }

    companion object {
        private const val TAG = "AudioActivity"
        private const val DEFAULT_BITRATE_BPS = 128_000
    }
}
