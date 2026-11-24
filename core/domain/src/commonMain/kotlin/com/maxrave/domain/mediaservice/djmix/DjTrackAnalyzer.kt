package com.maxrave.domain.mediaservice.djmix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * What the DJ transition renderer needs to know about a song, measured once from its whole
 * decoded audio (44.1 kHz stereo) and small enough to cache as JSON.
 */
@Serializable
data class DjTrackAnalysis(
    val version: Int = VERSION,
    val sampleRate: Int = DjDsp.SAMPLE_RATE,
    val totalFrames: Long,
    /** librosa-style global tempo (a value on librosa's lag grid). */
    val bpm: Double,
    /** Beat times in seconds. */
    val beats: List<Double>,
    /** Key tonic pitch class 0..11 (C = 0) and mode. */
    val keyTonic: Int,
    val keyMinor: Boolean,
    val keyConfidence: Double,
    /** Linear gain that puts the loud frames' median at -16 dBFS (djmix.loudness_gain). */
    val loudnessGain: Double,
    /** djmix.music_end of the loudness-matched song, in seconds. */
    val musicEndSec: Double,
    /** djmix.first_sound of the loudness-matched song, in seconds. */
    val firstSoundSec: Double,
    /** Start of the first sustained full-strength stretch (djmix5's B entry target), or null. */
    val fullStrengthSec: Double? = null,
    /**
     * Share of the spectral energy in the percussive part (median-filter HPSS over a few excerpts
     * of the song's body), 0..1: drums-led vs. sustained/vocal-led. Null before [VERSION] 2.
     */
    val percussiveRatio: Double? = null,
    /** Mean onset strength over the whole song (BeatTracker's envelope): how punchy it is. */
    val onsetMean: Double? = null,
) {
    val keyName: String get() = KeyDetector.NOTES[keyTonic] + if (keyMinor) "m" else ""

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** 2: adds [percussiveRatio] and [onsetMean] (smart next-track order). */
        const val VERSION = 2
        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        fun decode(text: String): DjTrackAnalysis? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.version == VERSION }
    }
}

/**
 * Builds a [DjTrackAnalysis] from a song decoded in chunks, so the whole song never has to sit in
 * memory as 44.1 kHz stereo: it keeps 50 ms frame energies and a 22.05 kHz mono copy (about
 * 5 MB per minute).
 */
class DjTrackAnalyzer {
    private val energies = ArrayList<Double>()
    private var energyAcc = 0.0
    private var energyCount = 0
    private val decimator = HalfbandDecimator()
    private var total = 0L

    /** [interleaved] stereo float frames at 44.1 kHz ([frames] of them). */
    fun feed(
        interleaved: FloatArray,
        frames: Int = interleaved.size / 2,
    ) {
        for (i in 0 until frames) {
            val l = interleaved[2 * i]
            val r = interleaved[2 * i + 1]
            energyAcc += l.toDouble() * l + r.toDouble() * r
            energyCount++
            if (energyCount == DjDsp.LEVEL_HOP) {
                energies.add(energyAcc / (2 * DjDsp.LEVEL_HOP))
                energyAcc = 0.0
                energyCount = 0
            }
            decimator.push((l + r) * 0.5f)
        }
        total += frames
    }

    fun feed(pcm: StereoPcm) {
        val chunk = 65_536
        val buf = FloatArray(chunk * 2)
        var pos = 0
        while (pos < pcm.frames) {
            val n = minOf(chunk, pcm.frames - pos)
            for (i in 0 until n) {
                buf[2 * i] = pcm.left[pos + i]
                buf[2 * i + 1] = pcm.right[pos + i]
            }
            feed(buf, n)
            pos += n
        }
    }

    fun finish(): DjTrackAnalysis {
        val mono = decimator.finish()
        val beat = BeatTracker.track(mono, tightness = 120.0)
        val key = KeyDetector.detect(mono)
        val e = energies.toDoubleArray()
        val gain = DjDsp.loudnessGain(e)
        val db = DjDsp.levelsDb(e, gain)
        val sr = DjDsp.SAMPLE_RATE.toDouble()
        val firstSound = DjDsp.firstSound(db) / sr
        val musicEnd = DjDsp.musicEnd(db, total.toInt()) / sr
        return DjTrackAnalysis(
            totalFrames = total,
            bpm = beat.bpm,
            beats = beat.beatSeconds.toList(),
            keyTonic = key.tonic,
            keyMinor = key.minor,
            keyConfidence = key.confidence,
            loudnessGain = gain,
            musicEndSec = musicEnd,
            firstSoundSec = firstSound,
            fullStrengthSec = DjDsp.fullStrength(db)?.let { it / sr },
            percussiveRatio = DjMatch.percussiveRatio(mono, firstSound, musicEnd),
            onsetMean = beat.onsetMean,
        )
    }

    companion object {
        fun analyze(pcm: StereoPcm): DjTrackAnalysis = DjTrackAnalyzer().apply { feed(pcm) }.finish()

        /** 44.1 kHz stereo to the 22.05 kHz mono the beat and key detectors read. */
        fun mono22k(pcm: StereoPcm): FloatArray {
            val d = HalfbandDecimator()
            for (i in 0 until pcm.frames) d.push((pcm.left[i] + pcm.right[i]) * 0.5f)
            return d.finish()
        }
    }
}

/**
 * Streaming decimation by two with a 129-tap Kaiser-windowed sinc (pass band to 0.95 of the new
 * Nyquist), zero-phase: output j is centred on input 2j.
 */
internal class HalfbandDecimator {
    private val half = 64
    private val len = 2 * half + 1
    private val taps: FloatArray

    // Ring buffer written twice, so the last [len] inputs are always contiguous.
    private val ring = FloatArray(2 * len)
    private var inputCount = 0L
    private var out = FloatArray(1 shl 16)
    private var outSize = 0

    init {
        val interp = SincInterpolator(cutoff = 0.475, zeroCrossings = 15)
        val raw = FloatArray(len) { k -> interp.at(floatArrayOf(1f), (k - half).toDouble()) }
        val sum = raw.sum()
        taps = FloatArray(len) { raw[it] / sum }
    }

    fun push(v: Float) {
        val idx = (inputCount % len).toInt()
        ring[idx] = v
        ring[idx + len] = v
        inputCount++
        // Window = ring[idx + 1 .. idx + len], oldest first; its centre is input inputCount - 1 - half.
        val centre = inputCount - 1 - half
        if (centre >= 0 && centre % 2 == 0L) emit(idx + 1)
    }

    private fun emit(from: Int) {
        var s = 0f
        for (k in 0 until len) s += taps[k] * ring[from + k]
        if (outSize == out.size) out = out.copyOf(out.size * 2)
        out[outSize++] = s
    }

    fun finish(): FloatArray {
        // Flush with silence so the last inputs reach the centre.
        val pending = inputCount
        for (i in 0 until half) {
            val idx = (inputCount % len).toInt()
            ring[idx] = 0f
            ring[idx + len] = 0f
            inputCount++
            val centre = inputCount - 1 - half
            if (centre in 0 until pending && centre % 2 == 0L) emit(idx + 1)
        }
        return out.copyOf(max(0, outSize))
    }
}
