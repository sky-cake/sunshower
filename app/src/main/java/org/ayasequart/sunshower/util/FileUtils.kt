package org.ayasequart.sunshower.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

fun Uri.displayName(context: Context): String? {
    return try {
        context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (e: Exception) {
        null
    }
}

fun Uri.fileSize(context: Context): Long {
    return try {
        context.contentResolver.query(this, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    cursor.getLong(idx)
                } else {
                    0L
                }
            } ?: 0L
    } catch (e: Exception) {
        0L
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}