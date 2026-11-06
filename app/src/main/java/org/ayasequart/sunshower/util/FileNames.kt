package org.ayasequart.sunshower.util

private val INVALID_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")

fun String.sanitizedFileName(fallback: String): String =
    trim().replace(INVALID_FILE_CHARS, "").ifEmpty { fallback }

fun uriBaseName(name: String?): String? {
    val base = name?.substringBeforeLast('.')?.trim()
    return base?.takeIf { it.isNotEmpty() }
}
