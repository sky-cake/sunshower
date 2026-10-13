package org.ayasequart.sunshower.video

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

object VideoSaver {

    fun saveFile(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
        isVideo: Boolean
    ): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    availableName(context.contentResolver, collection, displayName)
                )
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) +
                        "/Sunshower"
                )
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            val output = context.contentResolver.openOutputStream(uri) ?: return null
            copy(file, output)
            return uri
        }
        val dir = context.getExternalFilesDir(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        ) ?: return null
        val dest = uniqueFile(dir, displayName)
        copy(file, dest.outputStream())
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dest)
    }

    private fun copy(file: File, output: java.io.OutputStream) {
        FileInputStream(file).use { input ->
            output.use { outputStream -> input.copyTo(outputStream) }
        }
    }

    private fun availableName(resolver: ContentResolver, collection: Uri, name: String): String {
        val used = HashSet<String>()
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    used.add(cursor.getString(idx))
                }
            }
        } catch (e: Exception) {
            return name
        }
        if (name !in used) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while ("$base ($n)$ext" in used) {
            n++
        }
        return "$base ($n)$ext"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }
}
