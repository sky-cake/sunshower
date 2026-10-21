package org.ayasequart.sunshower.edit

data class ImageOutputOptions(
    val format: Int,
    val jpegQuality: Int,
    val reducePercent: Int
) {
    companion object {
        const val AS_IS = 0
        const val AS_JPEG = 1
        const val AS_PNG = 2
        fun defaults() = ImageOutputOptions(AS_IS, 85, 0)
    }
}

data class VideoOutputOptions(
    val targetHeight: Int?,
    val targetFps: Int?,
    val quality: Int,
    val format: Int,
    val audio: Int,
    val audioKbps: Int
) {
    companion object {
        const val FORMAT_KEEP = 0
        const val FORMAT_MP4 = 1
        const val FORMAT_WEBM = 2
        const val AUDIO_KEEP = 0
        const val AUDIO_REMOVE = 1
        const val AUDIO_CUSTOM = 2
        fun defaults() = VideoOutputOptions(null, null, 5, FORMAT_KEEP, AUDIO_KEEP, 128)
    }
}
