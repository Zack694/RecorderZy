package com.recorderzy.app.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures microphone and/or internal-system audio, runs the noise suppressor
 * and voice-changer DSP, and feeds the resulting PCM frames into an AAC
 * MediaCodec wired to the supplied [MediaMuxer].
 *
 * The pipeline lives entirely on a single coroutine on the Default dispatcher
 * so it never trips the main thread; pause/resume is handled by atomically
 * skipping captured frames while keeping the underlying AudioRecord and
 * encoder open (so the AV-sync presentation timestamps stay monotonic).
 */
class AudioPipeline(
    private val projection: MediaProjection,
    private val muxer: SafeMuxer,
    private val mode: RecorderConfig.AudioMode,
    private val noiseSuppression: Boolean,
    private val voicePreset: RecorderConfig.VoicePreset,
) {
    @Volatile private var paused = false
    @Volatile private var stopped = false

    private val sampleRate = 48_000
    private val channelMask = AudioFormat.CHANNEL_IN_MONO
    private val pcmFormat = AudioFormat.ENCODING_PCM_16BIT

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val voiceDsp = VoiceChangerDsp(sampleRate).apply { preset = voicePreset }

    private var encoder: MediaCodec? = null
    private var encoderTrack: Int = -1
    private var encoderStarted = false

    private var captureJob: Job? = null

    fun setPaused(value: Boolean) { paused = value }
    fun isMuted(): Boolean = mode == RecorderConfig.AudioMode.MUTE

    fun start(scope: CoroutineScope) {
        if (isMuted()) return
        configureEncoder()
        configureRecorders()
        captureJob = scope.launch(Dispatchers.Default) {
            try {
                runCaptureLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "Audio capture loop crashed: ${t.message}", t)
            } finally {
                releaseAll()
            }
        }
    }

    fun stop() {
        stopped = true
        captureJob?.cancel()
    }

    private fun configureEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            /* channelCount= */ 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        encoder = codec
    }

    @SuppressLint("MissingPermission")
    private fun configureRecorders() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, pcmFormat)
            .coerceAtLeast(4096)
        val bufSize = minBuf * 2

        if (mode == RecorderConfig.AudioMode.MIC || mode == RecorderConfig.AudioMode.BOTH) {
            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelMask,
                    pcmFormat,
                    bufSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord = record
                    Log.i(TAG, "Microphone AudioRecord initialized successfully")
                    if (noiseSuppression && NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = runCatching {
                            NoiseSuppressor.create(record.audioSessionId).also { 
                                it.enabled = true
                                Log.i(TAG, "Noise suppressor enabled")
                            }
                        }.getOrNull()
                    }
                } else {
                    Log.w(TAG, "Mic AudioRecord failed to initialize (state=${record.state}, permission missing?)")
                    runCatching { record.release() }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission not granted: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create mic AudioRecord: ${e.message}", e)
            }
        }
        if (mode == RecorderConfig.AudioMode.INTERNAL || mode == RecorderConfig.AudioMode.BOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val capCfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val record = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(pcmFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(bufSize)
                        .setAudioPlaybackCaptureConfig(capCfg)
                        .build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        internalRecord = record
                        Log.i(TAG, "Internal audio AudioRecord initialized successfully")
                    } else {
                        Log.w(TAG, "Internal AudioRecord failed to initialize (state=${record.state})")
                        runCatching { record.release() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create internal AudioRecord: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Internal audio capture requires Android Q+ (current: ${Build.VERSION.SDK_INT})")
            }
        }
        
        // Log final audio setup
        val micOk = micRecord != null
        val intOk = internalRecord != null
        Log.i(TAG, "Audio setup complete - Mic: $micOk, Internal: $intOk, Mode: $mode")
    }

    private suspend fun runCaptureLoop() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()

        try {
            micRecord?.startRecording()
            internalRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            return
        }

        val frameSize = 1024
        val micBuf = ShortArray(frameSize)
        val internalBuf = ShortArray(frameSize)
        val mixed = ShortArray(frameSize)
        val voiced = ShortArray(frameSize)

        var ptsUs = 0L
        val usPerFrame = 1_000_000L * frameSize / sampleRate

        Log.i(TAG, "Audio capture loop started")
        
        while (!stopped && (captureJob?.isActive == true)) {
            // While paused we still drain the AudioRecord buffers to avoid an
            // overflow but we throw the data away. PTS is left untouched so
            // the next post-pause frame lines up with the video keyframe we
            // resumed on.
            val micRead = micRecord?.read(micBuf, 0, frameSize) ?: 0
            val intRead = internalRecord?.read(internalBuf, 0, frameSize) ?: 0
            if (paused) continue

            val srcCount: Int = when (mode) {
                RecorderConfig.AudioMode.MIC -> micRead
                RecorderConfig.AudioMode.INTERNAL -> intRead
                RecorderConfig.AudioMode.BOTH -> mixInto(micBuf, micRead, internalBuf, intRead, mixed)
                RecorderConfig.AudioMode.MUTE -> 0
            }
            if (srcCount <= 0) continue

            val srcBuf = when (mode) {
                RecorderConfig.AudioMode.MIC -> micBuf
                RecorderConfig.AudioMode.INTERNAL -> internalBuf
                RecorderConfig.AudioMode.BOTH -> mixed
                RecorderConfig.AudioMode.MUTE -> mixed
            }
            val produced = voiceDsp.process(srcBuf, srcCount, voiced)
            if (produced <= 0) continue

            // Push voiced PCM into the AAC encoder.
            try {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inputBuf = codec.getInputBuffer(inIdx) ?: continue
                    inputBuf.clear()
                    val byteBuf = java.nio.ByteBuffer.allocate(produced * 2).apply {
                        order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        asShortBuffer().put(voiced, 0, produced)
                    }.array()
                    inputBuf.put(byteBuf)
                    codec.queueInputBuffer(inIdx, 0, produced * 2, ptsUs, 0)
                    ptsUs += usPerFrame
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing audio input: ${e.message}")
            }

            // Drain encoded AAC frames -> muxer.
            drainEncoder(codec, info)
        }

        // EOS flush.
        try {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(codec, info, untilEos = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error during EOS flush: ${e.message}")
        }
        
        Log.i(TAG, "Audio capture loop finished")
    }

    private fun mixInto(
        a: ShortArray, ar: Int,
        b: ShortArray, br: Int,
        out: ShortArray,
    ): Int {
        val n = maxOf(ar, br)
        for (i in 0 until n) {
            val sa = if (i < ar) a[i].toInt() else 0
            val sb = if (i < br) b[i].toInt() else 0
            // Soft mix with 0.65 ducking on the louder source to avoid clipping.
            val mixed = (sa * 0.65f + sb * 0.65f).toInt().coerceIn(-32768, 32767)
            out[i] = mixed.toShort()
        }
        return n
    }

    private fun drainEncoder(codec: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean = false) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return else continue
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (encoderStarted) continue
                    encoderTrack = muxer.addTrack(codec.outputFormat)
                    encoderStarted = true
                    muxer.maybeStart()
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        muxer.writeSample(encoderTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun releaseAll() {
        runCatching { noiseSuppressor?.release() }
        runCatching { micRecord?.stop(); micRecord?.release() }
        runCatching { internalRecord?.stop(); internalRecord?.release() }
        runCatching { encoder?.stop(); encoder?.release() }
    }

    companion object {
        private const val TAG = "AudioPipeline"
    }
}
