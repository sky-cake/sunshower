package org.ayasequart.sunshower.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer

data class VideoInfo(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val videoMime: String?,
    val audioMime: String?,
    val totalBytes: Long,
    val videoTrackBytes: Long,
    val audioTrackBytes: Long,
    val sampleRate: Int,
    val channels: Int,
) {
    val hasAudio: Boolean get() = audioMime != null
    val hasVideo: Boolean get() = videoMime != null

    companion object {

        fun codecName(mime: String?): String = when (mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4"
            "video/3gpp" -> "H.263"
            "audio/mp4a-latm" -> "AAC"
            "audio/mpeg" -> "MP3"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/3gpp" -> "AMR-NB"
            "audio/amr-wb" -> "AMR-WB"
            "audio/raw" -> "PCM"
            "audio/flac" -> "FLAC"
            else -> mime ?: "unknown"
        }

        fun probe(context: Context, uri: Uri): VideoInfo {
            return try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return empty()
                pfd.use {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(pfd.fileDescriptor)
                        probeTracks(extractor, pfd.statSize)
                    } finally {
                        extractor.release()
                    }
                }
            } catch (e: Exception) {
                empty()
            }
        }

        private fun empty(): VideoInfo = VideoInfo(0L, 0, 0, 0, null, null, 0L, 0L, 0L, 0, 0)

        private fun probeTracks(extractor: MediaExtractor, totalBytes: Long): VideoInfo {
            var durationUs = 0L
            var width = 0
            var height = 0
            var frameRate = 0
            var videoMime: String? = null
            var audioMime: String? = null
            var sampleRate = 0
            var channels = 0
            var videoBytes = 0L
            var audioBytes = 0L
            var videoIdx = -1
            var audioIdx = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoIdx < 0 && mime.startsWith("video/") -> videoIdx = i
                    audioIdx < 0 && mime.startsWith("audio/") -> audioIdx = i
                }
            }
            if (videoIdx >= 0) {
                val f = extractor.getTrackFormat(videoIdx)
                videoMime = f.getString(MediaFormat.KEY_MIME)
                if (f.containsKey(MediaFormat.KEY_WIDTH)) {
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                }
                if (f.containsKey(MediaFormat.KEY_HEIGHT)) {
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                }
                val rotation =
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) {
                    val t = width
                    width = height
                    height = t
                }
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = try {
                        f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt().coerceAtLeast(0)
                    } catch (e: ClassCastException) {
                        f.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(0)
                    }
                }
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = f.getLong(MediaFormat.KEY_DURATION)
                }
            }
            if (audioIdx >= 0) {
                val f = extractor.getTrackFormat(audioIdx)
                audioMime = f.getString(MediaFormat.KEY_MIME)
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (durationUs <= 0 && f.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = f.getLong(MediaFormat.KEY_DURATION)
                }
            }
            if (videoIdx >= 0) extractor.selectTrack(videoIdx)
            if (audioIdx >= 0) extractor.selectTrack(audioIdx)
            if (videoIdx >= 0 || audioIdx >= 0) {
                var capacity = (1 shl 20)
                for (i in listOfNotNull(
                    if (videoIdx >= 0) extractor.getTrackFormat(videoIdx) else null,
                    if (audioIdx >= 0) extractor.getTrackFormat(audioIdx) else null
                )) {
                    if (i.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        capacity = maxOf(capacity, i.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                val buffer = ByteBuffer.allocate(capacity)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    when (extractor.sampleTrackIndex) {
                        videoIdx -> videoBytes += size
                        audioIdx -> audioBytes += size
                    }
                    extractor.advance()
                }
            }
            return VideoInfo(
                durationUs,
                width,
                height,
                frameRate,
                videoMime,
                audioMime,
                totalBytes,
                videoBytes,
                audioBytes,
                sampleRate,
                channels
            )
        }
    }
}
