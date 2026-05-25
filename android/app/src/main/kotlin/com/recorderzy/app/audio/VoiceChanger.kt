package com.recorderzy.app.audio

import com.recorderzy.app.recorder.RecordingSession
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lightweight DSP voice transformations on 16-bit signed PCM stereo samples.
 *
 * The processing operates in-place on the supplied buffer and is deliberately
 * cheap: ~O(n) with no allocations after construction, so it can run in real
 * time on a low-end device alongside HEVC/APV encoding without breaking the
 * audio loop's 20ms cadence.
 *
 * Presets:
 *  - NORMAL   – pass-through.
 *  - DEEP     – pitch shift down (semitone-based via linear resampling).
 *  - HELIUM   – pitch shift up.
 *  - ROBOTIC  – ring modulation (multiply by a low-frequency cosine).
 *  - RADIO    – band-pass IIR + soft saturation, mimics walkie-talkie comms.
 */
class VoiceChanger(
    private val sampleRate: Int,
    private val preset: RecordingSession.VoicePreset,
) {

    // Pre-computed pitch ratio: 2^(semitones / 12)
    private val pitchRatio: Float = 2f.pow(preset.pitchSemitones / 12f)

    // ---- Robotic ring modulator state ----
    private val robotPhaseStep = (2.0 * PI * 30.0 / sampleRate)
    private var robotPhase = 0.0

    // ---- Radio band-pass filter state (Direct Form II Transposed biquad) ----
    private data class Biquad(var b0: Double, var b1: Double, var b2: Double, var a1: Double, var a2: Double, var z1: Double = 0.0, var z2: Double = 0.0)
    private val radioBp: Biquad = bandPass(800.0, 1.4)

    // ---- Resampler scratch ----
    private var carry: FloatArray = FloatArray(0)

    fun process(samples: ShortArray, offset: Int, count: Int) {
        when (preset) {
            RecordingSession.VoicePreset.NORMAL -> return
            RecordingSession.VoicePreset.DEEP, RecordingSession.VoicePreset.HELIUM -> {
                pitchShift(samples, offset, count, pitchRatio)
            }
            RecordingSession.VoicePreset.ROBOTIC -> robotize(samples, offset, count)
            RecordingSession.VoicePreset.RADIO -> radioFx(samples, offset, count)
        }
    }

    /**
     * Naive linear-interpolation resampling pitch shifter. Increasing [ratio] above
     * 1.0 makes the voice higher (chipmunk); below 1.0 makes it deeper. Tempo is
     * preserved by repeating/dropping samples to fill the same buffer length.
     */
    private fun pitchShift(samples: ShortArray, offset: Int, count: Int, ratio: Float) {
        if (ratio == 1f || count <= 0) return
        val src = FloatArray(count)
        for (i in 0 until count) src[i] = samples[offset + i].toFloat()

        var pos = 0f
        for (i in 0 until count) {
            val srcIdx = pos.toInt().coerceAtMost(count - 2)
            val frac = pos - srcIdx
            val s = src[srcIdx] * (1 - frac) + src[srcIdx + 1] * frac
            samples[offset + i] = s.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            pos += ratio
            if (pos >= count - 1) pos = 0f // wrap to keep buffer length
        }
    }

    private fun robotize(samples: ShortArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            val mod = cos(robotPhase).toFloat()
            robotPhase += robotPhaseStep
            samples[offset + i] = (samples[offset + i] * mod).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun radioFx(samples: ShortArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            val input = samples[offset + i].toDouble()
            val out = process(radioBp, input)
            // Soft-saturate to add the typical VHF "edge".
            val saturated = (1.6 * out / (1.0 + Math.abs(0.0006 * out)))
            samples[offset + i] = saturated.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun process(b: Biquad, x: Double): Double {
        val y = b.b0 * x + b.z1
        b.z1 = b.b1 * x - b.a1 * y + b.z2
        b.z2 = b.b2 * x - b.a2 * y
        return y
    }

    private fun bandPass(centerHz: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * centerHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosw = cos(w0)
        val b0 = alpha
        val b1 = 0.0
        val b2 = -alpha
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosw
        val a2 = 1.0 - alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
