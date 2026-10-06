package com.example.sunshower.settings

import android.os.Bundle
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
import com.example.sunshower.R

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = SettingsStore.load(this)
        val fpsBar = findViewById<SeekBar>(R.id.fps_bar)
        val fpsValue = findViewById<TextView>(R.id.fps_value)
        fpsBar.progress = settings.fps - 1
        fpsValue.text = getString(R.string.fps_value, settings.fps)
        fpsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                fpsValue.text = getString(R.string.fps_value, progress + 1)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        val shortSides = intArrayOf(144, 240, 360, 480, 720)
        val resolutionSpinner = findViewById<Spinner>(R.id.resolution_spinner)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, shortSides.map { "${it}p" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter
        resolutionSpinner.setSelection(shortSides.indexOf(settings.shortSide).coerceAtLeast(0))

        val loopOnce = findViewById<RadioButton>(R.id.loop_once)
        val loopInfinite = findViewById<RadioButton>(R.id.loop_infinite)
        val loopCountRadio = findViewById<RadioButton>(R.id.loop_count)
        val countInput = findViewById<EditText>(R.id.loop_count_input)

        fun selectLoop(mode: Int) {
            loopOnce.isChecked = mode == 0
            loopInfinite.isChecked = mode == 1
            loopCountRadio.isChecked = mode == 2
            countInput.isEnabled = mode == 2
        }

        selectLoop(settings.loopMode)
        countInput.setText(settings.loopCount.toString())
        loopOnce.setOnClickListener { selectLoop(0) }
        loopInfinite.setOnClickListener { selectLoop(1) }
        loopCountRadio.setOnClickListener { selectLoop(2) }

        val ditheringSwitch = findViewById<CheckBox>(R.id.dithering_switch)
        ditheringSwitch.isChecked = settings.dithering

        val fileNameInput = findViewById<EditText>(R.id.file_name_input)
        fileNameInput.setText(settings.fileName)

        findViewById<Button>(R.id.done_button).setOnClickListener {
            val loopMode = when {
                loopOnce.isChecked -> 0
                loopInfinite.isChecked -> 1
                else -> 2
            }
            val fileName = fileNameInput.text.toString()
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .ifEmpty { SettingsStore.DEFAULT_FILE_NAME }
            val s = Settings(
                fpsBar.progress + 1,
                shortSides[resolutionSpinner.selectedItemPosition.coerceAtLeast(0)],
                loopMode,
                countInput.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 1,
                ditheringSwitch.isChecked,
                fileName
            )
            SettingsStore.save(this, s)
            finish()
        }
    }
}
