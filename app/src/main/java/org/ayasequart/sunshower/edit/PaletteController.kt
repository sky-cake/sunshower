package org.ayasequart.sunshower.edit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.ayasequart.sunshower.R

class PaletteController(
    private val context: Context,
    private val row: LinearLayout,
    private val onColorChanged: (Int) -> Unit
) {

    var selectedColor: Int = PALETTE.first()
        private set

    private val paletteSwatches = ArrayList<Pair<View, GradientDrawable>>()
    private val recentSwatchViews = ArrayList<View>()
    private val recentColors = ArrayList<Int>()

    fun build() {
        val density = context.resources.displayMetrics.density
        row.addView(createPickerSwatch(density))
        for (color in PALETTE) {
            row.addView(createSwatch(color, density))
        }
        for (color in loadRecentColors()) {
            recentColors.add(color)
            recentSwatchViews.add(createSwatch(color, density).also { row.addView(it) })
        }
        paletteSwatches.first().second.setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
    }

    private fun createPickerSwatch(density: Float): View {
        val rainbow = TextView(context)
        val lp = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
        lp.marginEnd = (8 * density).toInt()
        rainbow.layoutParams = lp
        rainbow.gravity = Gravity.CENTER
        rainbow.text = "+"
        rainbow.setTextColor(android.graphics.Color.WHITE)
        rainbow.textSize = 22f
        rainbow.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(
                0xFFE53935.toInt(),
                0xFFFDD835.toInt(),
                0xFF43A047.toInt(),
                0xFF1E88E5.toInt(),
                0xFF8E24AA.toInt()
            )
        }
        rainbow.setOnClickListener { showColorPicker() }
        return rainbow
    }

    private fun createSwatch(color: Int, density: Float): View {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.cornerRadius = 8 * density
        shape.setColor(color)
        val swatch = View(context)
        val lp = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
        lp.marginEnd = (8 * density).toInt()
        swatch.layoutParams = lp
        swatch.background = shape
        swatch.setOnClickListener {
            selectedColor = color
            highlightSwatch(shape, density)
            onColorChanged(color)
        }
        paletteSwatches.add(swatch to shape)
        return swatch
    }

    private fun highlightSwatch(shape: GradientDrawable, density: Float) {
        for ((_, s) in paletteSwatches) {
            s.setStroke((2 * density).toInt(), android.graphics.Color.TRANSPARENT)
        }
        shape.setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
    }

    private fun addCustomSwatch(color: Int) {
        saveRecentColor(color)
        rebuildRecentSwatches()
        recentSwatchViews.firstOrNull()?.performClick() ?: run {
            selectedColor = color
            onColorChanged(color)
        }
    }

    private fun rebuildRecentSwatches() {
        val old = ArrayList(recentSwatchViews)
        for (v in old) {
            row.removeView(v)
        }
        for (i in paletteSwatches.indices.reversed()) {
            if (paletteSwatches[i].first in old) {
                paletteSwatches.removeAt(i)
            }
        }
        recentSwatchViews.clear()
        val density = context.resources.displayMetrics.density
        for (color in recentColors) {
            val view = createSwatch(color, density)
            recentSwatchViews.add(view)
            row.addView(view)
        }
    }

    private fun saveRecentColor(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > 3) {
            recentColors.removeAt(recentColors.size - 1)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT_COLORS, recentColors.joinToString(",") { "%08X".format(it) })
            .apply()
    }

    private fun loadRecentColors(): List<Int> {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RECENT_COLORS, null)
                ?.split(',')
                ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLong(16)?.toInt() }
                .orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showColorPicker() {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val picker = ColorPickerView(context)
        picker.setInitialColor(selectedColor)
        val pickerLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        pickerLp.bottomMargin = (8 * density).toInt()
        container.addView(picker, pickerLp)
        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            setBackgroundColor(selectedColor)
        }
        container.addView(preview)
        picker.onColorChanged = { c -> preview.setBackgroundColor(c) }

        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.color_picker_title)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                addCustomSwatch(picker.color)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PREFS_NAME = "editor_palette"
        private const val KEY_RECENT_COLORS = "recent_colors"
        private val PALETTE = intArrayOf(
            0xFF212121.toInt(),
            0xFFFAFAFA.toInt(),
            0xFF1E88E5.toInt(),
            0xFF43A047.toInt(),
            0xFFE53935.toInt()
        )
    }
}
