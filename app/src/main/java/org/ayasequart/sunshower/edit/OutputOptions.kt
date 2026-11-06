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

