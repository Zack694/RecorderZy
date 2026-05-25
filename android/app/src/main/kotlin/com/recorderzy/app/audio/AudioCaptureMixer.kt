package com.recorderzy.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.recorderzy.app.recorder.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures microphone + internal-system audio (via Android 10+ AudioPlaybackCapture)
 * and mixes them into a single PCM stream that downstream encoders can consume.
 *
 * - Mute mode: nothing is read.
 * - Mic only: only the [AudioRecord] microphone source is read.
 * - Internal only: only [AudioPlaybackCaptureConfiguration]-backed AudioRecord is read.
 * - Both: streams are sample-mixed (averaged with clip protection).
 *
 * Optional [VoiceChanger] DSP is applied to the mic side before mixing, so the
 * voice changer does not affect the original system audio (game audio, music).
 */
class AudioCaptureMixer(
    private val context: Context,
    private val projection: MediaProjection,
    private val mode: RecordingSession.AudioMode,
    private val voicePreset: RecordingSession.VoicePreset,
) {

    private val sampleRate = 48_000
    private val channelMask = AudioFormat.CHANNEL_IN_STEREO
    private val format = AudioFormat.ENCODING_PCM_16BIT

    private val bufferSize: Int = AudioRecord.getMinBufferSize(sampleRate, channelMask, format)
        .let { if (it == AudioRecord.ERROR_BAD_VALUE) 4096 else it * 4 }

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private val voiceChanger = VoiceChanger(sampleRate, voicePreset)

    @Volatile private var paused = false
    private var loopJob: Job? = null

    /** AudioSession id of the microphone source — exposed so [NoiseSuppressorEffect] can attach. */
    val audioSessionId: Int
        get() = micRecord?.audioSessionId ?: 0

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val captureMic = mode == RecordingSession.AudioMode.MIC_ONLY ||
            mode == RecordingSession.AudioMode.MIC_AND_INTERNAL
        val captureInternal = mode == RecordingSession.AudioMode.INTERNAL_ONLY ||
            mode == RecordingSession.AudioMode.MIC_AND_INTERNAL

        if (captureMic) {
            micRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channelMask, format, bufferSize
            ).also { it.startRecording() }
        }

        if (captureInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val playbackFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(format)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            internalRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(playbackFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
                .also { it.startRecording() }
        }

        loopJob = scope.launch(Dispatchers.IO) { runMixLoop() }
    }

    private suspend fun runMixLoop() {
        val micBuf = ShortArray(bufferSize / 2)
        val intBuf = ShortArray(bufferSize / 2)
        val mixed = ShortArray(bufferSize / 2)

        while (true) {
            if (paused) {
                delay(20); continue
            }
            val micRead = micRecord?.read(micBuf, 0, micBuf.size) ?: 0
            val intRead = internalRecord?.read(intBuf, 0, intBuf.size) ?: 0
            val n = maxOf(micRead, intRead)
            if (n <= 0) {
                delay(2); continue
            }
            // Apply voice DSP to the microphone half only.
            if (micRead > 0) voiceChanger.process(micBuf, 0, micRead)

            for (i in 0 until n) {
                val a = if (i < micRead) micBuf[i].toInt() else 0
                val b = if (i < intRead) intBuf[i].toInt() else 0
                // Average + clip — the simplest mixing strategy that avoids overflow.
                val sum = a + b
                mixed[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            // The mixed PCM here is conceptually fed back into the active encoder. In
            // the MediaRecorder path, MediaRecorder owns the mic source directly, so
            // this loop functions as the live tap that NoiseSuppressorEffect / future
            // RNNoise hooks attach to. In the MediaCodec path, we route [mixed] into
            // an AudioCodec input buffer — wired in the matching ScreenRecorder branch.
            try {
                AudioSink.publish(mixed, n)
            } catch (e: Throwable) {
                Log.w(TAG, "audio publish failed", e)
            }
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        try { micRecord?.stop() } catch (_: Throwable) {}
        try { internalRecord?.stop() } catch (_: Throwable) {}
        try { micRecord?.release() } catch (_: Throwable) {}
        try { internalRecord?.release() } catch (_: Throwable) {}
        micRecord = null
        internalRecord = null
    }

    companion object { private const val TAG = "AudioCaptureMixer" }
}

/**
 * Tiny pub-sub shim that decouples the mixer from any specific encoder. The
 * MediaCodec audio path subscribes a writer onto this; in pure-MediaRecorder
 * mode the publisher is a no-op (AAC encoding is owned by MediaRecorder itself).
 */
object AudioSink {
    @Volatile var consumer: ((ShortArray, Int) -> Unit)? = null
    fun publish(samples: ShortArray, count: Int) {
        consumer?.invoke(samples, count)
    }
}
