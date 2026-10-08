package org.ayasequart.sunshower.kuroba

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.gif.GifSaver
import org.ayasequart.sunshower.ui.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

class KurobaActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var fileInfoText: TextView
    private lateinit var extLabel: TextView
    private lateinit var pickButton: Button
    private lateinit var applyButton: Button
    private lateinit var fileNameInput: EditText
    private lateinit var checksumSwitch: CheckBox
    private lateinit var exifSwitch: CheckBox
    private lateinit var metadataSwitch: CheckBox
    private lateinit var reencodeGroup: RadioGroup
    private lateinit var qualityRow: View
    private lateinit var qualityValue: TextView
    private lateinit var qualityBar: SeekBar
    private lateinit var reduceRow: View
    private lateinit var reduceValue: TextView
    private lateinit var reduceBar: SeekBar
    private var pickedUri: Uri? = null
    private var pickedWidth = 0
    private var pickedHeight = 0
    private var currentFormat: Bitmap.CompressFormat? = null
    private var savedMessageJob: Job? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            applyButton.isEnabled = true
            statusText.text = ""
            readPickedDimensions(uri)
            currentFormat = contentResolver.openInputStream(uri)?.use {
                ImageReencoder.getImageFormat(it)
            }
            val fullName = displayName(uri)
            if (fullName != null) {
                val base = fullName.substringBeforeLast('.').trim()
                if (base.isNotEmpty()) {
                    fileNameInput.setText(base)
                }
            }
            fileInfoText.text = if (fullName != null) {
                getString(R.string.kuroba_file_info, fullName)
            } else {
                ""
            }
            updateExtLabel()
            updateReduceLabel(reduceBar.progress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kuroba)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            (16 * resources.displayMetrics.density).toInt()
        )
        val options = KurobaOptionsStore.load(this)

        statusText = findViewById(R.id.kuroba_status)
        fileInfoText = findViewById(R.id.kuroba_file_info)
        extLabel = findViewById(R.id.kuroba_ext_label)
        pickButton = findViewById(R.id.kuroba_pick_button)
        applyButton = findViewById(R.id.kuroba_apply_button)
        fileNameInput = findViewById(R.id.kuroba_file_name_input)
        checksumSwitch = findViewById(R.id.change_checksum_switch)
        exifSwitch = findViewById(R.id.fix_exif_switch)
        metadataSwitch = findViewById(R.id.remove_metadata_switch)
        reencodeGroup = findViewById(R.id.reencode_group)
        qualityRow = findViewById(R.id.quality_row)
        qualityValue = findViewById(R.id.quality_value)
        qualityBar = findViewById(R.id.quality_bar)
        reduceRow = findViewById(R.id.reduce_row)
        reduceValue = findViewById(R.id.reduce_value)
        reduceBar = findViewById(R.id.reduce_bar)

        fileNameInput.setText(options.fileName)
        checksumSwitch.isChecked = options.changeChecksum
        exifSwitch.isChecked = options.fixExif
        metadataSwitch.isChecked = options.removeMetadata
        reencodeGroup.check(
            when (options.reencodeType) {
                KurobaOptions.AS_JPEG -> R.id.reencode_as_jpeg
                KurobaOptions.AS_PNG -> R.id.reencode_as_png
                else -> R.id.reencode_as_is
            }
        )

        qualityBar.progress = options.jpegQuality - 1
        qualityValue.text = getString(R.string.quality_value, qualityBar.progress + 1)
        qualityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                qualityValue.text = getString(R.string.quality_value, progress + 1)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        reduceBar.progress = options.reducePercent
        updateReduceLabel(reduceBar.progress)
        reduceBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateReduceLabel(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        reencodeGroup.setOnCheckedChangeListener { _, _ -> updateOptionVisibility() }
        updateOptionVisibility()

        findViewById<Button>(R.id.kuroba_set_unix_button).setOnClickListener {
            fileNameInput.setText((System.currentTimeMillis() / 1000L).toString())
        }

        pickButton.setOnClickListener { pickMedia.launch("image/*") }
        applyButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { applyToPickedFile() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::fileNameInput.isInitialized) {
            KurobaOptionsStore.save(this, optionsFromUi())
        }
    }

    private fun updateOptionVisibility() {
        val type = reencodeType()
        qualityRow.visibility = if (type == KurobaOptions.AS_JPEG) View.VISIBLE else View.GONE
        reduceRow.visibility = if (type == KurobaOptions.AS_IS) View.GONE else View.VISIBLE
        updateExtLabel()
    }

    private fun updateExtLabel() {
        extLabel.text = when (reencodeType()) {
            KurobaOptions.AS_JPEG -> ".jpg"
            KurobaOptions.AS_PNG -> ".png"
            else -> when (currentFormat) {
                Bitmap.CompressFormat.JPEG -> ".jpg"
                Bitmap.CompressFormat.WEBP -> ".webp"
                else -> ".png"
            }
        }
    }

    private fun reencodeType(): Int = when (reencodeGroup.checkedRadioButtonId) {
        R.id.reencode_as_jpeg -> KurobaOptions.AS_JPEG
        R.id.reencode_as_png -> KurobaOptions.AS_PNG
        else -> KurobaOptions.AS_IS
    }

    private fun optionsFromUi(): KurobaOptions {
        return KurobaOptions(
            fileName = fileNameInput.text.toString()
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .ifEmpty { KurobaOptions.DEFAULT_FILE_NAME },
            changeChecksum = checksumSwitch.isChecked,
            fixExif = exifSwitch.isChecked,
            removeMetadata = metadataSwitch.isChecked,
            reencodeType = reencodeType(),
            jpegQuality = qualityBar.progress + 1,
            reducePercent = reduceBar.progress
        )
    }

    private fun readPickedDimensions(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                pickedWidth = bounds.outWidth
                pickedHeight = bounds.outHeight
            }
        } catch (e: Exception) {
            pickedWidth = 0
            pickedHeight = 0
        }
    }

    private fun updateReduceLabel(progress: Int) {
        val effective = progress.coerceAtMost(MAX_REDUCE_PERCENT)
        if (pickedWidth > 0 && pickedHeight > 0) {
            val outW = pickedWidth * (100 - effective) / 100
            val outH = pickedHeight * (100 - effective) / 100
            reduceValue.text =
                getString(R.string.reduce_value_dims, pickedWidth, pickedHeight, outW, outH)
        } else {
            reduceValue.text = getString(R.string.reduce_value, 100 - effective)
        }
    }

    private suspend fun applyToPickedFile() {
        val uri = pickedUri ?: return
        val raw = optionsFromUi()
        KurobaOptionsStore.save(this, raw)
        val opts = if (raw.reencodeType == KurobaOptions.AS_IS) {
            raw.copy(jpegQuality = 100, reducePercent = 0)
        } else {
            raw
        }
        withContext(Dispatchers.Main) { setBusy(true) }
        try {
            val resolver = contentResolver
            val sourceFormat = resolver.openInputStream(uri)?.use {
                ImageReencoder.getImageFormat(it)
            }
            val rotate = if (opts.fixExif) {
                ImageReencoder.exifRotationDegrees(resolver, uri)
            } else {
                0f
            }
            val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: throw IOException("could not decode file")
            val out = ImageReencoder.reencode(decoded, opts, rotate)

            val format = when (opts.reencodeType) {
                KurobaOptions.AS_JPEG -> Bitmap.CompressFormat.JPEG
                KurobaOptions.AS_PNG -> Bitmap.CompressFormat.PNG
                else -> sourceFormat ?: Bitmap.CompressFormat.PNG
            }
            val isJpeg = format == Bitmap.CompressFormat.JPEG
            val ext = if (isJpeg) "jpg" else "png"
            val mime = if (isJpeg) "image/jpeg" else "image/png"
            val quality = if (isJpeg) opts.jpegQuality else 100
            val bytes = ByteArrayOutputStream().also {
                out.compress(format, quality, it)
            }.toByteArray()

            val base = opts.fileName
            GifSaver.saveBytes(this, bytes, "$base.$ext", mime)
            withContext(Dispatchers.Main) { onSaved() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                savedMessageJob?.cancel()
                val reason = e.message ?: e.javaClass.simpleName
                statusText.text = getString(R.string.save_failed_reason, reason)
            }
        } finally {
            withContext(Dispatchers.Main) { setBusy(false) }
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun setBusy(busy: Boolean) {
        pickButton.isEnabled = !busy
        applyButton.isEnabled = !busy && pickedUri != null
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
        private const val SAVED_MESSAGE_MS = 3000L
        private const val MAX_REDUCE_PERCENT = 95
    }
}
