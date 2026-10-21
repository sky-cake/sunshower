package org.ayasequart.sunshower.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

object CodecSupport {

    fun hardwareEncoderMimes(): Set<String> {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && isHardware(it) }
                .flatMap { it.supportedTypes.toList() }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google") || name.startsWith("c2.android") ||
            name.startsWith("c2.google") || name.startsWith("omx.ffmpeg"))
    }
}
