package org.ayasequart.sunshower.util

import java.util.Locale

fun extForMime(mime: String): String = when (mime) {
    "video/webm", "audio/webm" -> ".webm"
    "audio/mp4" -> ".m4a"
    else -> ".mp4"
}

fun durationText(durationUs: Long): String? {
    if (durationUs <= 0) return null
    val totalSeconds = durationUs / 1_000_000L
    return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
}

fun durationTextPrecise(durationUs: Long): String? {
    if (durationUs <= 0) return null
    val tenths = durationUs / 100_000L
    return "%d:%02d.%d".format(Locale.US, tenths / 600, (tenths / 10) % 60, tenths % 10)
}
