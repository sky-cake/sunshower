package org.ayasequart.sunshower.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min

data class VideoOutput(val file: File, val mime: String)

object VideoProcessor {

    suspend fun runTransformer(
        context: Context,
        uri: Uri,
        outFile: File,
        removeAudio: Boolean,
        targetHeight: Int?,
        targetFps: Int?,
        videoMime: String?,
        audioMime: String?,
        videoBitrateBps: Int?,
        audioBitrateBps: Int?,
        onProgress: (Int) -> Unit
    ): VideoOutput = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var poller: Job? = null
            var emulatorAssetLoaderFactory: ExoPlayerAssetLoader.Factory? = null
            try {
                val videoEffects = ArrayList<Effect>()
                if (targetHeight != null) {
                    videoEffects.add(
                        Presentation.createForHeight(targetHeight)
                            .copyWithUnsetSideRoundedTo(2)
                    )
                }
                if (targetFps != null) {
                    videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(targetFps.toFloat()))
                }
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setRemoveAudio(removeAudio)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()
                val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
                val composition = Composition.Builder(sequence).build()
                val encoderBuilder = DefaultEncoderFactory.Builder(context)
                if (videoBitrateBps != null) {
                    encoderBuilder.setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(videoBitrateBps)
                            .build()
                    )
                }
                if (isEmulator()) {
                    val decoderFactory = DefaultDecoderFactory.Builder(context)
                        .setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                            MediaCodecSelector.DEFAULT
                                .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                                .filter { !it.hardwareAccelerated }
                        }
                        .build()
                    encoderBuilder.setVideoEncoderSelector(
                        EncoderSelector { mimeType ->
                            ImmutableList.copyOf(
                                EncoderUtil.getSupportedEncoders(mimeType).filter {
                                    !EncoderUtil.isHardwareAccelerated(it, mimeType)
                                }
                            )
                        }
                    )
                    emulatorAssetLoaderFactory = ExoPlayerAssetLoader.Factory(
                        context, decoderFactory, Clock.DEFAULT
                    )
                }
                val transformerBuilder = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            poller?.cancel()
                            if (cont.isActive) {
                                cont.resume(VideoOutput(outFile, mimeForExtension(outFile)))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            poller?.cancel()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .setEncoderFactory(encoderBuilder.build())
                if (emulatorAssetLoaderFactory != null) {
                    transformerBuilder.setAssetLoaderFactory(emulatorAssetLoaderFactory!!)
                }
                if (videoMime != null) transformerBuilder.setVideoMimeType(videoMime)
                if (audioMime != null) transformerBuilder.setAudioMimeType(audioMime)
                val transformer = transformerBuilder.build()
                cont.invokeOnCancellation {
                    transformer.cancel()
                    poller?.cancel()
                }
                transformer.start(composition, outFile.absolutePath)
                poller = CoroutineScope(Dispatchers.Default).launch {
                    while (isActive) {
                        delay(250)
                        try {
                            val holder = ProgressHolder()
                            val state = transformer.getProgress(holder)
                            if (state != Transformer.PROGRESS_STATE_NOT_STARTED &&
                                holder.progress in 0..100
                            ) {
                                onProgress(holder.progress)
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                poller?.cancel()
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    private fun mimeForExtension(file: File): String {
        return if (file.name.endsWith(".webm")) "video/webm" else "video/mp4"
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.contains("sdk") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")
    }

    suspend fun extractAudio(
        context: Context,
        uri: Uri,
        outFile: File,
        passthrough: Boolean,
        bitrateBps: Int,
        durationUs: Long,
        onProgress: (Int) -> Unit
    ): VideoOutput = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("cannot open file")
        pfd.use {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                val trackIndex = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IllegalStateException("no audio track")
                val inFormat = extractor.getTrackFormat(trackIndex)
                val srcMime = inFormat.getString(MediaFormat.KEY_MIME)!!
                extractor.selectTrack(trackIndex)
                if (passthrough) {
                    passthroughToM4a(extractor, inFormat, outFile)
                } else {
                    decodeEncode(
                        extractor,
                        inFormat,
                        srcMime,
                        outFile,
                        bitrateBps,
                        durationUs,
                        onProgress
                    )
                }
            } finally {
                extractor.release()
            }
        }
    }

    private fun passthroughToM4a(
        extractor: MediaExtractor,
        inFormat: MediaFormat,
        outFile: File
    ): VideoOutput {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val dst = muxer.addTrack(inFormat)
            muxer.start()
            val maxSize = if (inFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                inFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1 shl 20
            }
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                info.offset = 0
                muxer.writeSampleData(dst, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return VideoOutput(outFile, "audio/mp4")
    }

    private fun decodeEncode(
        extractor: MediaExtractor,
        inFormat: MediaFormat,
        srcMime: String,
        outFile: File,
        bitrateBps: Int,
        durationUs: Long,
        onProgress: (Int) -> Unit
    ): VideoOutput {
        val outMime = MediaFormat.MIMETYPE_AUDIO_AAC
        val encInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.supportedTypes.contains(outMime)
        } ?: throw IllegalStateException("no $outMime encoder")
        val srcRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44100
        }
        val srcChannels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }
        val outRate = nearestRate(encInfo, srcRate)
        val outChannels = min(srcChannels, 2)
        val encFormat = MediaFormat.createAudioFormat(outMime, outRate, outChannels)
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        encFormat.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(inFormat, null, null, 0)
        val encoder = MediaCodec.createByCodecName(encInfo.name)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val muxerHolder = MuxerHolder(
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            "audio/mp4"
        )
        val pending = ByteBuffer.allocate(1 shl 20).order(ByteOrder.nativeOrder())
        var floatInput = false
        var actualRate = srcRate
        var actualChannels = srcChannels
        var decoderFormatSeen = false
        var firstPtsUs = 0L
        var havePts = false
        var totalSamples = 0L
        var muxTrack = -1
        var muxStarted = false
        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderDone = false
        var encoderEosQueued = false
        try {
            decoder.start()
            encoder.start()
            val info = MediaCodec.BufferInfo()
            while (!encoderDone) {
                if (!decoderInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                onProgress(
                                    (extractor.sampleTime * 100 / durationUs).toInt().coerceIn(0, 99)
                                )
                            }
                        }
                    }
                }
                if (!decoderOutputDone) {
                    val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        decoderFormatSeen = false
                    } else if (outIdx >= 0) {
                        if (!decoderFormatSeen) {
                            val fmt = decoder.outputFormat
                            floatInput = isFloat(fmt)
                            actualRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            } else {
                                srcRate
                            }
                            actualChannels =
                                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                } else {
                                    srcChannels
                                }
                            decoderFormatSeen = true
                        }
                        val buf = decoder.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            if (!havePts) {
                                firstPtsUs = info.presentationTimeUs
                                havePts = true
                            }
                            queuePcm(
                                buf, info, actualRate, actualChannels, floatInput,
                                outRate, outChannels, pending
                            )
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) {
                            decoderOutputDone = true
                        }
                    }
                }
                totalSamples = feedEncoder(
                    pending, encoder, outRate, outChannels, firstPtsUs, totalSamples
                )
                if (decoderOutputDone && pending.position() == 0 && !encoderEosQueued) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(
                            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encoderEosQueued = true
                    }
                }
                val encIdx =
                    encoder.dequeueOutputBuffer(info, if (decoderOutputDone) 10_000 else 0)
                when {
                    encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxerHolder.muxer.addTrack(encoder.outputFormat)
                        muxerHolder.muxer.start()
                        muxStarted = true
                    }
                    encIdx >= 0 -> {
                        val buf = encoder.getOutputBuffer(encIdx)!!
                        if (info.size > 0 && muxStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxerHolder.muxer.writeSampleData(muxTrack, buf, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(encIdx, false)
                        if (eos) encoderDone = true
                    }
                }
            }
            if (!muxStarted) throw IllegalStateException("encoder produced no output")
            muxerHolder.muxer.stop()
        } finally {
            try {
                decoder.stop()
            } catch (e: Exception) {
            }
            decoder.release()
            try {
                encoder.stop()
            } catch (e: Exception) {
            }
            encoder.release()
            muxerHolder.release()
        }
        return VideoOutput(outFile, muxerHolder.mime)
    }

    private fun isFloat(format: MediaFormat): Boolean {
        return format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
            android.media.AudioFormat.ENCODING_PCM_FLOAT
    }

    private class MuxerHolder(val muxer: MediaMuxer, val mime: String) {
        fun release() {
            try {
                muxer.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun queuePcm(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        inRate: Int,
        inChannels: Int,
        floatInput: Boolean,
        outRate: Int,
        outChannels: Int,
        pending: ByteBuffer
    ) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val shorts: ShortArray = if (floatInput) {
            val floats = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
            ShortArray(floats.remaining()) { (floats.get().toDouble().coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort() }
        } else {
            val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
            ShortArray(sb.remaining()) { sb.get() }
        }
        val processed = processPcm(shorts, inRate, inChannels, outRate, outChannels)
        val byteCount = processed.size * 2
        ensureCapacity(pending, byteCount)
        pending.asShortBuffer().put(processed)
        pending.position(pending.position() + byteCount)
    }

    private fun processPcm(
        input: ShortArray,
        inRate: Int,
        inChannels: Int,
        outRate: Int,
        outChannels: Int
    ): ShortArray {
        var samples = input
        var channels = inChannels
        if (channels > outChannels) {
            val frames = samples.size / channels
            val down = ShortArray(frames * outChannels)
            var o = 0
            for (f in 0 until frames) {
                for (c in 0 until outChannels) {
                    var sum = 0
                    var count = 0
                    var ch = c
                    while (ch < channels) {
                        sum += samples[f * channels + ch]
                        count++
                        ch += outChannels
                    }
                    down[o++] = (sum / count).toShort()
                }
            }
            samples = down
            channels = outChannels
        }
        if (inRate == outRate) return samples
        val inFrames = samples.size / channels
        if (inFrames == 0) return ShortArray(0)
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ShortArray(outFrames * channels)
        val step = inRate.toDouble() / outRate
        for (f in 0 until outFrames) {
            val pos = f * step
            val i0 = pos.toInt().coerceIn(0, inFrames - 1)
            val i1 = min(i0 + 1, inFrames - 1)
            val frac = pos - i0
            for (c in 0 until channels) {
                val s0 = samples[i0 * channels + c]
                val s1 = samples[i1 * channels + c]
                out[f * channels + c] = (s0 + (s1 - s0) * frac).toInt().toShort()
            }
        }
        return out
    }

    private fun ensureCapacity(pending: ByteBuffer, needed: Int) {
        if (pending.remaining() >= needed) return
        val grown = ByteBuffer.allocate(
            maxOf(pending.capacity() * 2, pending.position() + needed)
        ).order(ByteOrder.nativeOrder())
        pending.flip()
        grown.put(pending)
        pending.clear()
        grown.flip()
        pending.put(grown as ByteBuffer)
    }

    private fun feedEncoder(
        pending: ByteBuffer,
        encoder: MediaCodec,
        outRate: Int,
        outChannels: Int,
        firstPtsUs: Long,
        totalSamplesIn: Long
    ): Long {
        var totalSamples = totalSamplesIn
        while (pending.position() > 0) {
            val available = pending.position()
            val inIdx = encoder.dequeueInputBuffer(0)
            if (inIdx < 0) break
            val inBuf = encoder.getInputBuffer(inIdx)!!
            inBuf.clear()
            var chunk = min(available, inBuf.remaining())
            chunk -= chunk % (2 * outChannels)
            if (chunk <= 0) break
            pending.flip()
            val oldLimit = pending.limit()
            pending.limit(chunk)
            inBuf.put(pending)
            pending.limit(oldLimit)
            pending.compact()
            val frames = chunk / (2 * outChannels)
            val pts = firstPtsUs + totalSamples * 1_000_000L / outRate
            totalSamples += frames
            encoder.queueInputBuffer(inIdx, 0, chunk, pts, 0)
        }
        return totalSamples
    }

    private fun nearestRate(info: MediaCodecInfo, rate: Int): Int {
        val rates = try {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                .audioCapabilities?.supportedSampleRates
        } catch (e: IllegalArgumentException) {
            null
        } ?: return rate
        return rates.minBy { abs(it - rate) }
    }
}
