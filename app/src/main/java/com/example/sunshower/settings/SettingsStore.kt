package com.example.sunshower.settings

import android.content.Context

data class Settings(
    val fps: Int,
    val shortSide: Int,
    val aspect: String,
    val loopMode: Int,
    val loopCount: Int,
    val dithering: Boolean,
    val fileName: String
)

object SettingsStore {
    private const val PREFS = "sunshower_settings"
    private const val KEY_FPS = "fps"
    private const val KEY_SHORT_SIDE = "short_side"
    private const val KEY_ASPECT = "aspect"
    private const val KEY_LOOP_MODE = "loop_mode"
    private const val KEY_LOOP_COUNT = "loop_count"
    private const val KEY_DITHERING = "dithering"
    private const val KEY_FILE_NAME = "file_name"
    const val DEFAULT_FILE_NAME = "image"
    const val DEFAULT_ASPECT = "1:1"
    val ASPECTS = listOf("1:1", "4:3", "16:9", "9:16")

    fun aspectRatio(aspect: String): Float {
        val parts = aspect.split(":")
        val w = parts.getOrNull(0)?.toFloatOrNull() ?: 1f
        val h = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        if (w <= 0f || h <= 0f) return 1f
        return w / h
    }

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            p.getInt(KEY_FPS, 10),
            p.getInt(KEY_SHORT_SIDE, 360),
            p.getString(KEY_ASPECT, DEFAULT_ASPECT)?.takeIf { it in ASPECTS } ?: DEFAULT_ASPECT,
            p.getInt(KEY_LOOP_MODE, 1),
            p.getInt(KEY_LOOP_COUNT, 1),
            p.getBoolean(KEY_DITHERING, true),
            (p.getString(KEY_FILE_NAME, DEFAULT_FILE_NAME) ?: DEFAULT_FILE_NAME)
                .trim()
                .ifEmpty { DEFAULT_FILE_NAME }
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, s.fps)
            .putInt(KEY_SHORT_SIDE, s.shortSide)
            .putString(KEY_ASPECT, s.aspect)
            .putInt(KEY_LOOP_MODE, s.loopMode)
            .putInt(KEY_LOOP_COUNT, s.loopCount)
            .putBoolean(KEY_DITHERING, s.dithering)
            .putString(KEY_FILE_NAME, s.fileName.trim().ifEmpty { DEFAULT_FILE_NAME })
            .apply()
    }
}
