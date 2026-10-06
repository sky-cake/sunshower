package com.example.sunshower.kuroba

import android.content.Context

data class KurobaOptions(
    val fileName: String,
    val changeChecksum: Boolean,
    val fixExif: Boolean,
    val removeMetadata: Boolean,
    val reencodeType: Int,
    val jpegQuality: Int,
    val reducePercent: Int
) {
    companion object {
        const val AS_IS = 0
        const val AS_JPEG = 1
        const val AS_PNG = 2
        const val DEFAULT_FILE_NAME = "image"
    }
}

object KurobaOptionsStore {
    private const val PREFS = "kuroba_settings"
    private const val KEY_FILE_NAME = "file_name"
    private const val KEY_CHANGE_CHECKSUM = "change_checksum"
    private const val KEY_FIX_EXIF = "fix_exif"
    private const val KEY_REMOVE_METADATA = "remove_metadata"
    private const val KEY_REENCODE_TYPE = "reencode_type"
    private const val KEY_JPEG_QUALITY = "jpeg_quality"
    private const val KEY_REDUCE_PERCENT = "reduce_percent"

    fun load(context: Context): KurobaOptions {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return KurobaOptions(
            fileName = (p.getString(KEY_FILE_NAME, KurobaOptions.DEFAULT_FILE_NAME)
                ?: KurobaOptions.DEFAULT_FILE_NAME)
                .trim()
                .ifEmpty { KurobaOptions.DEFAULT_FILE_NAME },
            changeChecksum = p.getBoolean(KEY_CHANGE_CHECKSUM, false),
            fixExif = p.getBoolean(KEY_FIX_EXIF, false),
            removeMetadata = p.getBoolean(KEY_REMOVE_METADATA, false),
            reencodeType = p.getInt(KEY_REENCODE_TYPE, KurobaOptions.AS_IS).coerceIn(0, 2),
            jpegQuality = p.getInt(KEY_JPEG_QUALITY, 100).coerceIn(1, 100),
            reducePercent = p.getInt(KEY_REDUCE_PERCENT, 0).coerceIn(0, 100)
        )
    }

    fun save(context: Context, o: KurobaOptions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FILE_NAME, o.fileName.trim().ifEmpty { KurobaOptions.DEFAULT_FILE_NAME })
            .putBoolean(KEY_CHANGE_CHECKSUM, o.changeChecksum)
            .putBoolean(KEY_FIX_EXIF, o.fixExif)
            .putBoolean(KEY_REMOVE_METADATA, o.removeMetadata)
            .putInt(KEY_REENCODE_TYPE, o.reencodeType.coerceIn(0, 2))
            .putInt(KEY_JPEG_QUALITY, o.jpegQuality.coerceIn(1, 100))
            .putInt(KEY_REDUCE_PERCENT, o.reducePercent.coerceIn(0, 100))
            .apply()
    }
}
