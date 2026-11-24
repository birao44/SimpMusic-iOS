package com.maxrave.data.mediaservice.dj

import com.maxrave.domain.mediaservice.djmix.DjDsp
import com.maxrave.domain.mediaservice.djmix.StereoPcm
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetPreferPreciseDurationAndTimingKey
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSURL
import kotlin.math.max
import kotlin.math.min

/**
 * AVAssetReader → 44.1 kHz stereo Float32 (interleaved), the format the DJ analysis and renderer
 * read. Plain Kotlin/Native, no Swift. Every call blocks its thread: run it on the pipeline's
 * background dispatcher.
 */
@OptIn(ExperimentalForeignApi::class)
internal object DjAudioDecoder {
    private const val SR = DjDsp.SAMPLE_RATE

    class DecodeException(
        message: String,
    ) : Exception(message)

    /**
     * Decodes the whole of [path] in chunks, handing each one to [sink] as interleaved stereo
     * frames (`sink(buffer, frames)`; the buffer is reused, copy what you keep). Returns the number
     * of frames delivered.
     */
    suspend fun stream(
        path: String,
        sink: (FloatArray, Int) -> Unit,
    ): Long {
        var total = 0L
        read(path, null) { buffer, frames, _ ->
            sink(buffer, frames)
            total += frames
        }
        return total
    }

    /**
     * Frames [[from], [to]) of [path] as [StereoPcm] (zero where the song has none). Reads only
     * that range (plus a second of decoder lead-in), placed by each buffer's presentation time.
     */
    suspend fun window(
        path: String,
        from: Int,
        to: Int,
    ): StereoPcm {
        val n = max(0, to - from)
        val left = FloatArray(n)
        val right = FloatArray(n)
        if (n == 0) return StereoPcm(left, right)
        val leadIn = SR
        val start = max(0, from - leadIn)
        read(path, start.toLong() to (to - start).toLong()) { buffer, frames, ptsFrame ->
            // Frame ptsFrame + i of the song is buffer frame i.
            val first = max(0L, from - ptsFrame).toInt()
            val last = min(frames.toLong(), to - ptsFrame).toInt()
            for (i in first until last) {
                val o = (ptsFrame + i - from).toInt()
                left[o] = buffer[2 * i]
                right[o] = buffer[2 * i + 1]
            }
        }
        return StereoPcm(left, right)
    }

    /** [range] = start frame to frame count, or null for the whole file. */
    private suspend fun read(
        path: String,
        range: Pair<Long, Long>?,
        chunk: (FloatArray, Int, Long) -> Unit,
    ) {
        val asset =
            AVURLAsset.URLAssetWithURL(
                NSURL.fileURLWithPath(path),
                options = mapOf<Any?, Any?>(AVURLAssetPreferPreciseDurationAndTimingKey to true),
            )
        @Suppress("DEPRECATION")
        val track =
            asset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").firstOrNull() as? AVAssetTrack
                ?: throw DecodeException("No audio track in $path")
        val reader = AVAssetReader(asset = asset, error = null)
        val settings =
            mapOf<Any?, Any?>(
                AVFormatIDKey to kAudioFormatLinearPCM.toInt(),
                AVSampleRateKey to SR.toDouble(),
                AVNumberOfChannelsKey to 2,
                AVLinearPCMBitDepthKey to 32,
                AVLinearPCMIsFloatKey to true,
                AVLinearPCMIsBigEndianKey to false,
                AVLinearPCMIsNonInterleaved to false,
            )
        val output = AVAssetReaderTrackOutput(track = track, outputSettings = settings)
        output.alwaysCopiesSampleData = false
        if (!reader.canAddOutput(output)) throw DecodeException("Reader refuses PCM output for $path")
        reader.addOutput(output)
        if (range != null) {
            reader.timeRange = CMTimeRangeMake(CMTimeMake(range.first, SR), CMTimeMake(range.second, SR))
        }
        if (!reader.startReading()) throw DecodeException("startReading failed: ${reader.error?.localizedDescription}")

        var buffer = FloatArray(16_384)
        var sequential = range?.first ?: 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val done =
                    autoreleasepool {
                        val sample = output.copyNextSampleBuffer() ?: return@autoreleasepool true
                        try {
                            val block = CMSampleBufferGetDataBuffer(sample)
                            if (block != null) {
                                val bytes = CMBlockBufferGetDataLength(block).toLong()
                                val floats = (bytes / 4).toInt()
                                if (floats > buffer.size) buffer = FloatArray(floats)
                                if (floats > 0) {
                                    buffer.usePinned { pinned ->
                                        CMBlockBufferCopyDataBytes(block, 0.convert(), (floats * 4L).convert(), pinned.addressOf(0))
                                    }
                                }
                                val frames = floats / 2
                                val pts =
                                    CMSampleBufferGetPresentationTimeStamp(sample).useContents {
                                        if (timescale > 0 && (flags.toInt() and 1) != 0) {
                                            // kCMTimeFlags_Valid: the buffer's own position, in frames.
                                            ((value.toDouble() * SR) / timescale.toDouble() + 0.5).toLong()
                                        } else {
                                            -1L
                                        }
                                    }
                                val at = if (pts >= 0) pts else sequential
                                chunk(buffer, frames, at)
                                sequential = at + frames
                            }
                        } finally {
                            CFRelease(sample)
                        }
                        false
                    }
                if (done) break
            }
            if (reader.status == AVAssetReaderStatusFailed) {
                throw DecodeException("Decode failed: ${reader.error?.localizedDescription}")
            }
        } finally {
            reader.cancelReading()
        }
    }
}
