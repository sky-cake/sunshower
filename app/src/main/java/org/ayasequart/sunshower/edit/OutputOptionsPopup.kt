package org.ayasequart.sunshower.edit

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.ayasequart.sunshower.R

object OutputOptionsPopup {

    fun showImageOptions(
        activity: android.app.Activity,
        sourceFormat: Bitmap.CompressFormat?,
        sourceWidth: Int,
        sourceHeight: Int,
        initial: ImageOutputOptions,
        onOk: (ImageOutputOptions) -> Unit
    ) {
        val builder = CardBuilder(activity)

        val formatRow = builder.addRadioRow(
            activity.getString(R.string.image_format),
            listOf(
                activity.getString(R.string.reencode_as_is),
                activity.getString(R.string.reencode_jpeg),
                activity.getString(R.string.reencode_png)
            )
        )
        formatRow.checkIndex(
            when (initial.format) {
                ImageOutputOptions.AS_JPEG -> 1
                ImageOutputOptions.AS_PNG -> 2
                else -> 0
            }
        )

        val qualityRow = builder.addSliderRow(
            activity.getString(R.string.quality_label),
            99, (initial.jpegQuality - 1).coerceIn(0, 99),
            { activity.getString(R.string.quality_value, it + 1) }
        )

        fun reduceLabel(progress: Int): String {
            val w = sourceWidth.coerceAtLeast(1)
            val h = sourceHeight.coerceAtLeast(1)
            val outW = w * (100 - progress) / 100
            val outH = h * (100 - progress) / 100
            return activity.getString(R.string.reduce_value_dims, w, h, outW, outH)
        }
        val reduceRow = builder.addSliderRow(
            activity.getString(R.string.reduce_label),
            95, initial.reducePercent.coerceIn(0, 95), ::reduceLabel
        )

        fun updateVisibility() {
            qualityRow.container.visibility =
                if (formatRow.checkedIndex() == ImageOutputOptions.AS_JPEG) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            reduceRow.container.visibility =
                if (formatRow.checkedIndex() == ImageOutputOptions.AS_IS) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
        }
        formatRow.setOnCheckedListener { updateVisibility() }
        updateVisibility()

        builder.finish(activity.getString(R.string.cancel), activity.getString(R.string.ok)) {
            onOk(
                ImageOutputOptions(
                    format = formatRow.checkedIndex(),
                    jpegQuality = qualityRow.bar.progress + 1,
                    reducePercent = reduceRow.bar.progress
                )
            )
        }
    }

    fun showVideoOptions(
        activity: android.app.Activity,
        sourceWebm: Boolean,
        asisAvailable: Boolean,
        webmAvailable: Boolean,
        vp9Available: Boolean,
        hasAudio: Boolean,
        initial: VideoOutputOptions,
        onOk: (VideoOutputOptions) -> Unit
    ) {
        val builder = CardBuilder(activity)

        val resolutions = listOf(
            activity.getString(R.string.video_res_original),
            activity.getString(R.string.video_res_320),
            activity.getString(R.string.video_res_480),
            activity.getString(R.string.video_res_640),
            activity.getString(R.string.video_res_720)
        )
        val resolutionSpinner = builder.addSpinnerRow(
            activity.getString(R.string.resolution_label),
            resolutions,
            when (initial.targetHeight) {
                320 -> 1
                480 -> 2
                640 -> 3
                720 -> 4
                else -> 0
            }
        )

        val frameRates = listOf(
            activity.getString(R.string.video_fps_original),
            activity.getString(R.string.video_fps_24),
            activity.getString(R.string.video_fps_25),
            activity.getString(R.string.video_fps_30)
        )
        val fpsSpinner = builder.addSpinnerRow(
            activity.getString(R.string.video_fps_label),
            frameRates,
            when (initial.targetFps) {
                24 -> 1
                25 -> 2
                30 -> 3
                else -> 0
            }
        )

        val qualityRow = builder.addSliderRow(
            activity.getString(R.string.video_quality_label),
            9, (initial.quality - 1).coerceIn(0, 9),
            { activity.getString(R.string.video_quality_value, it + 1) }
        )

        val formats = ArrayList<Pair<String, Int>>()
        if (asisAvailable) {
            formats.add(
                activity.getString(
                    R.string.video_fmt_asis, if (sourceWebm) "WebM" else "MP4"
                ) to VideoOutputOptions.FORMAT_KEEP
            )
        }
        if (sourceWebm) {
            formats.add(
                activity.getString(R.string.video_format_mp4) to VideoOutputOptions.FORMAT_MP4
            )
        } else if (webmAvailable) {
            formats.add(
                activity.getString(
                    R.string.video_format_webm, if (vp9Available) "VP9" else "VP8"
                ) to VideoOutputOptions.FORMAT_WEBM
            )
        }
        val formatRow = builder.addRadioRow(
            activity.getString(R.string.video_format_label),
            formats.map { it.first }
        )
        val initialFormatIndex = when (initial.format) {
            VideoOutputOptions.FORMAT_MP4 ->
                formats.indexOfFirst { it.second == VideoOutputOptions.FORMAT_MP4 }
            VideoOutputOptions.FORMAT_WEBM ->
                formats.indexOfFirst { it.second == VideoOutputOptions.FORMAT_WEBM }
            else ->
                formats.indexOfFirst { it.second == VideoOutputOptions.FORMAT_KEEP }
        }
        formatRow.checkIndex(if (initialFormatIndex >= 0) initialFormatIndex else 0)

        val audioRow = builder.addRadioRow(
            activity.getString(R.string.audio_label),
            listOf(
                activity.getString(R.string.audio_option_keep),
                activity.getString(R.string.audio_option_remove),
                activity.getString(R.string.audio_option_custom)
            )
        )
        audioRow.checkIndex(
            when (initial.audio) {
                VideoOutputOptions.AUDIO_REMOVE -> 1
                VideoOutputOptions.AUDIO_CUSTOM -> 2
                else -> 0
            }
        )
        audioRow.setEnabled(1, hasAudio)
        audioRow.setEnabled(2, hasAudio)
        if (!hasAudio && initial.audio != VideoOutputOptions.AUDIO_KEEP) {
            audioRow.checkIndex(0)
        }

        val audioKbpsRow = builder.addSliderRow(
            activity.getString(R.string.audio_quality_label),
            9, kbpsToProgress(initial.audioKbps),
            { activity.getString(R.string.audio_quality_value, kbpsForProgress(it)) }
        )

        fun updateVisibility() {
            audioKbpsRow.container.visibility =
                if (audioRow.checkedIndex() == VideoOutputOptions.AUDIO_CUSTOM) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
        audioRow.setOnCheckedListener { updateVisibility() }
        updateVisibility()

        builder.finish(activity.getString(R.string.cancel), activity.getString(R.string.ok)) {
            val height = when (resolutionSpinner.selectedItemPosition) {
                1 -> 320
                2 -> 480
                3 -> 640
                4 -> 720
                else -> null
            }
            val fps = when (fpsSpinner.selectedItemPosition) {
                1 -> 24
                2 -> 25
                3 -> 30
                else -> null
            }
            onOk(
                VideoOutputOptions(
                    targetHeight = height,
                    targetFps = fps,
                    quality = qualityRow.bar.progress + 1,
                    format = formats[formatRow.checkedIndex().coerceIn(0, formats.size - 1)].second,
                    audio = when (audioRow.checkedIndex()) {
                        1 -> VideoOutputOptions.AUDIO_REMOVE
                        2 -> VideoOutputOptions.AUDIO_CUSTOM
                        else -> VideoOutputOptions.AUDIO_KEEP
                    },
                    audioKbps = kbpsForProgress(audioKbpsRow.bar.progress)
                )
            )
        }
    }

    private fun kbpsForProgress(progress: Int): Int = 48 + progress * 208 / 9

    private fun kbpsToProgress(kbps: Int): Int =
        (((kbps - 48) * 9) / 208f).toInt().coerceIn(0, 9)

    private class RadioRow(
        private val group: RadioGroup,
        private val ids: List<Int>
    ) {
        fun checkIndex(index: Int) {
            if (index in ids.indices) group.check(ids[index])
        }

        fun checkedIndex(): Int {
            val idx = ids.indexOf(group.checkedRadioButtonId)
            return if (idx < 0) 0 else idx
        }

        fun setEnabled(index: Int, enabled: Boolean) {
            if (index in ids.indices) group.getChildAt(index).isEnabled = enabled
        }

        fun setOnCheckedListener(listener: () -> Unit) {
            group.setOnCheckedChangeListener { _, _ -> listener() }
        }
    }

    private class SliderRow(val container: LinearLayout, val bar: SeekBar)

    private class CardBuilder(private val activity: android.app.Activity) {

        val card = LinearLayout(activity)

        init {
            val density = activity.resources.displayMetrics.density
            card.orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            card.setPadding(pad, pad, pad, pad / 2)
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(0xFF212121.toInt())
                setStroke((1 * density).toInt(), 0xFF3A3A3A.toInt())
            }
            card.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.output_options)
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFFFAFAFA.toInt())
                },
                linearLp().also { it.bottomMargin = (10 * density).toInt() }
            )
        }

        fun finish(cancelText: String, okText: String, onOk: () -> Unit) {
            val density = activity.resources.displayMetrics.density
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val scrim = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0x88000000.toInt())
                isClickable = true
                isFocusable = true
            }
            val holder = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            holder.addView(
                card,
                FrameLayout.LayoutParams(
                    (340 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            scrim.addView(holder)

            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val cancel = Button(activity).apply {
                text = cancelText
                setOnClickListener { content.removeView(scrim) }
            }
            val ok = Button(activity).apply {
                text = okText
                setOnClickListener {
                    content.removeView(scrim)
                    onOk()
                }
            }
            buttons.addView(
                cancel,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            buttons.addView(
                ok,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.leftMargin = (8 * density).toInt() }
            )
            card.addView(
                buttons,
                linearLp().also { it.topMargin = (14 * density).toInt() }
            )
            scrim.setOnClickListener { content.removeView(scrim) }
            content.addView(scrim)
        }

        fun addRadioRow(label: String, options: List<String>): RadioRow {
            addSmallLabel(label)
            val group = RadioGroup(activity)
            group.orientation = LinearLayout.HORIZONTAL
            val ids = ArrayList<Int>(options.size)
            for (option in options) {
                val radio = RadioButton(activity)
                radio.text = option
                radio.textSize = 13f
                radio.id = View.generateViewId()
                ids.add(radio.id)
                group.addView(
                    radio,
                    RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            card.addView(group, linearLp())
            return RadioRow(group, ids)
        }

        fun addSpinnerRow(label: String, options: List<String>, initialIndex: Int): Spinner {
            addSmallLabel(label)
            val spinner = Spinner(activity)
            val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, options)
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setPopupBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(0xFF2C2C2C.toInt())
                    cornerRadius = 8 * density()
                    setStroke((1 * density()).toInt(), 0xFF4A4A4A.toInt())
                }
            )
            spinner.setSelection(initialIndex.coerceIn(0, options.size - 1))
            card.addView(spinner, linearLp().also { it.topMargin = (2 * density()).toInt() })
            return spinner
        }

        fun addSliderRow(
            label: String,
            max: Int,
            initialProgress: Int,
            labelFor: (Int) -> String
        ): SliderRow {
            val density = density()
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            container.addView(
                TextView(activity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(0xFFBDBDBD.toInt())
                },
                linearLp()
            )
            val value = TextView(activity).apply {
                textSize = 13f
                setTextColor(0xFFFAFAFA.toInt())
                text = labelFor(initialProgress)
            }
            container.addView(value, linearLp())
            val bar = SeekBar(activity)
            bar.max = max
            bar.progress = initialProgress
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = labelFor(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            container.addView(bar, linearLp().also { it.topMargin = (4 * density).toInt() })
            card.addView(container, linearLp().also { it.topMargin = (10 * density).toInt() })
            return SliderRow(container, bar)
        }

        private fun density() = activity.resources.displayMetrics.density

        private fun addSmallLabel(label: String) {
            val density = density()
            val view = TextView(activity)
            view.text = label
            view.textSize = 14f
            view.setTextColor(0xFFBDBDBD.toInt())
            card.addView(view, linearLp().also { it.topMargin = (10 * density).toInt() })
        }

        private fun linearLp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
}
