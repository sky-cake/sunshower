package com.example.sunshower.settings

import android.content.Context

data class Settings(
    val fps: Int,
    val shortSide: Int,
    val loopMode: Int,
    val loopCount: Int,
    val dithering: Boolean,
    val fileName: String
)

object SettingsStore {
    private const val PREFS = "sunshower_settings"
    private const val KEY_FPS = "fps"
    private const val KEY_SHORT_SIDE = "short_side"
    private const val KEY_LOOP_MODE = "loop_mode"
    private const val KEY_LOOP_COUNT = "loop_count"
    private const val KEY_DITHERING = "dithering"
    private const val KEY_FILE_NAME = "file_name"
    const val DEFAULT_FILE_NAME = "image"

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            p.getInt(KEY_FPS, 10),
            p.getInt(KEY_SHORT_SIDE, 360),
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
            .putInt(KEY_LOOP_MODE, s.loopMode)
            .putInt(KEY_LOOP_COUNT, s.loopCount)
            .putBoolean(KEY_DITHERING, s.dithering)
            .putString(KEY_FILE_NAME, s.fileName.trim().ifEmpty { DEFAULT_FILE_NAME })
            .apply()
    }
}
