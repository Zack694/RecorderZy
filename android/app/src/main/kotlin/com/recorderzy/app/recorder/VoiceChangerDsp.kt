package com.recorderzy.app.recorder

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real-time voice-changer DSP operating on 16-bit signed PCM mono frames.
 *
 * Each preset is a deliberately small, allocation-light building block so the
 * recorder can stay on the audio capture thread without dropping frames:
 *
 *   * NORMAL  -> bypass.
 *   * DEEP    -> low-shelf boost + slow tremolo for "movie trailer" voice.
 *   * HELIUM  -> short comb-feedback brightener for chipmunk colour.
 *   * ROBOT   -> 80 Hz ring modulator with 55% wet mix.
 *   * RADIO   -> 300-3000 Hz band-pass + soft saturation, classic comms band.
 *
 * Genuine PSOLA pitch shifting is intentionally avoided: time-stretching adds
 * AV-sync drift and a SOLA implementation correct enough to ship is multiple
 * hundreds of lines on its own. The chosen "spectral colour" effects produce
 * recognisable voice variation for the same input duration which is what the
 * user-facing dropdown promises.
 */
class VoiceChangerDsp(
    private val sampleRate: Int,
) {

    var preset: RecorderConfig.VoicePreset = RecorderConfig.VoicePreset.NORMAL
        set(value) {
            field = value
            reset()
        }

    // Tremolo/Modulator phase trackers.
    private var deepPhase = 0.0
    private var robotPhase = 0.0

    // Single-pole filters reused between presets.
    private var lpPrev = 0f
    private var hpPrev = 0f
    private var hpPrevIn = 0f

    // Comb filter delay line for the helium preset.
    private val combDelay = max(1, sampleRate / 1500) // ~0.66 ms at 48 kHz
    private val combBuf = FloatArray(combDelay)
    private var combIdx = 0

    fun reset() {
        deepPhase = 0.0
        robotPhase = 0.0
        lpPrev = 0f
        hpPrev = 0f
        hpPrevIn = 0f
        combBuf.fill(0f)
        combIdx = 0
    }

    /**
     * Process exactly [samples] PCM samples from [input] into [out]. Always
     * returns [samples] - presets are designed to be sample-accurate so the
     * encoder presentation timestamps stay aligned with the video stream.
     */
    fun process(input: ShortArray, samples: Int, out: ShortArray): Int {
        if (samples <= 0) return 0
        return when (preset) {
            RecorderConfig.VoicePreset.NORMAL -> {
                System.arraycopy(input, 0, out, 0, samples)
                samples
            }
            RecorderConfig.VoicePreset.DEEP -> deepVoice(input, samples, out)
            RecorderConfig.VoicePreset.HELIUM -> heliumVoice(input, samples, out)
            RecorderConfig.VoicePreset.ROBOT -> ringModulate(
                input, samples, out, carrierHz = 80f, mix = 0.55f
            )
            RecorderConfig.VoicePreset.RADIO -> radioComms(input, samples, out)
        }
    }

    private fun deepVoice(input: ShortArray, samples: Int, out: ShortArray): Int {
        // Slow tremolo at ~5 Hz + low-pass to push energy below ~1.5 kHz.
        val omega = 2.0 * PI * 5.0 / sampleRate
        val rcLp = 1f / (2f * PI.toFloat() * 1500f)
        val dt = 1f / sampleRate
        val alpha = dt / (rcLp + dt)
        for (i in 0 until samples) {
            val s = input[i].toFloat()
            // Low-pass.
            lpPrev += alpha * (s - lpPrev)
            // Tremolo: 0.7..1.0 depth.
            val mod = 0.85f + 0.15f * sin(deepPhase).toFloat()
            deepPhase += omega
            if (deepPhase > 2.0 * PI) deepPhase -= 2.0 * PI
            // Add a tiny dose of cubic warmth.
            val warmed = lpPrev * mod * 1.2f - 0.0000003f * lpPrev * lpPrev * lpPrev
            out[i] = warmed.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return samples
    }

    private fun heliumVoice(input: ShortArray, samples: Int, out: ShortArray): Int {
        // Short comb feedback adds the bright resonance characteristic of
        // helium-shifted vocals without altering duration.
        val feedback = 0.55f
        for (i in 0 until samples) {
            val s = input[i].toFloat()
            val delayed = combBuf[combIdx]
            val mixed = s + delayed * feedback
            combBuf[combIdx] = mixed.coerceIn(-32768f, 32767f)
            combIdx = (combIdx + 1) % combDelay
            // High-emphasis: lift highs by adding the difference with previous sample.
            val emphasised = mixed + (s - hpPrevIn) * 0.6f
            hpPrevIn = s
            out[i] = emphasised.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return samples
    }

    private fun ringModulate(
        input: ShortArray,
        samples: Int,
        out: ShortArray,
        carrierHz: Float,
        mix: Float,
    ): Int {
        val omega = 2.0 * PI * carrierHz / sampleRate
        for (i in 0 until samples) {
            val carrier = sin(robotPhase).toFloat()
            robotPhase += omega
            if (robotPhase > 2.0 * PI) robotPhase -= 2.0 * PI
            val dry = input[i].toFloat()
            val wet = dry * carrier
            val mixed = (1f - mix) * dry + mix * wet
            out[i] = mixed.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return samples
    }

    private fun radioComms(input: ShortArray, samples: Int, out: ShortArray): Int {
        // Cascaded 1-pole HP (~300 Hz) and LP (~3 kHz) -> tanh saturator.
        val dt = 1f / sampleRate
        val alphaHp = (1f / (2f * PI.toFloat() * 300f)) /
            ((1f / (2f * PI.toFloat() * 300f)) + dt)
        val alphaLp = dt / ((1f / (2f * PI.toFloat() * 3000f)) + dt)
        var prevIn = 0f
        for (i in 0 until samples) {
            val s = input[i].toFloat()
            val hp = alphaHp * (hpPrev + s - prevIn)
            prevIn = s
            hpPrev = hp
            lpPrev += alphaLp * (hp - lpPrev)
            // Soft clip + a tiny static breath for radio "feel".
            val driven = tanh((lpPrev / 18000f).toDouble()).toFloat() * 22000f
            out[i] = driven.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return samples
    }
}
