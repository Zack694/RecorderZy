package com.recorderzy.app.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Screen recorder engine built on [MediaRecorder] + [MediaProjection] +
 * [VirtualDisplay].
 *
 * This intentionally uses the high-level MediaRecorder API rather than a
 * hand-rolled MediaCodec/MediaMuxer pipeline. MediaRecorder handles the
 * encoder, the MP4 muxer, audio capture and A/V sync internally and is far
 * more reliable across OEM devices (Xiaomi/HyperOS, MediaTek, etc.) - which
 * is exactly the approach mainstream recorders such as AZ Screen Recorder
 * take. The previous custom pipeline failed to start on several devices.
 *
 * Audio: MediaRecorder can only capture the microphone (it cannot do
 * AudioPlaybackCapture for internal/system audio). So MIC and BOTH capture
 * the mic; INTERNAL and MUTE record video only. Mic is only enabled when the
 * RECORD_AUDIO permission is actually granted.
 */
class ScreenRecorderEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val cfg: RecorderConfig,
    private val onState: (State) -> Unit,
) {

    enum class State { STARTING, RECORDING, PAUSED, STOPPING, STOPPED, ERROR }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null

    private var encWidth = 0
    private var encHeight = 0

    @Volatile private var paused = false
    @Volatile private var stopping = false
    @Volatile private var started = false

    private var workingFile: File? = null
    fun outputFile(): File? = workingFile

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------
    fun start() {
        try {
            onState(State.STARTING)

            val cacheDir = context.cacheDir
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IllegalStateException("Cache directory unavailable")
            }
            val out = File(cacheDir, "recorderzy_${System.currentTimeMillis()}.mp4")
            workingFile = out

            // Align to a multiple of 16 - hardware encoders reject odd /
            // non-aligned sizes (e.g. the Poco X6 5G's 1220x2712).
            encWidth = align16(cfg.widthPx).coerceAtLeast(16)
            encHeight = align16(cfg.heightPx).coerceAtLeast(16)

            val micEnabled = audioWantsMic() && hasRecordAudioPermission()
            val fps = cfg.frameRate.coerceIn(15, 120)

            // Try progressively safer configurations until one prepares. This
            // covers OEM encoders that reject HEVC, or that can't sustain the
            // requested resolution at high frame rates (level limits), or that
            // choke on the mic source.
            val attempts = listOf(
                Attempt(useHevc = true, mic = micEnabled, fps = fps),
                Attempt(useHevc = false, mic = micEnabled, fps = fps),
                Attempt(useHevc = false, mic = micEnabled, fps = 30),
                Attempt(useHevc = false, mic = false, fps = 30),
            )
            val out0 = out
            if (attempts.none { configureRecorder(out0, it.mic, it.useHevc, it.fps) }) {
                throw IllegalStateException("MediaRecorder could not be prepared")
            }

            configureVirtualDisplay()

            recorder?.start()
            started = true

            engineScope.launch { tickElapsed() }

            RecorderStateBus.publishPhase(RecorderStateBus.Phase.RECORDING)
            onState(State.RECORDING)
            Log.i(TAG, "Recording started @ ${encWidth}x${encHeight} mic=$micEnabled")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start recording: ${t.message}", t)
            RecorderStateBus.reset()
            releaseAll()
            onState(State.ERROR)
            throw t
        }
    }

    fun pause() {
        if (paused || !started) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                recorder?.pause()
            }
            paused = true
            RecorderStateBus.publishPhase(RecorderStateBus.Phase.PAUSED)
            onState(State.PAUSED)
        } catch (t: Throwable) {
            Log.w(TAG, "pause failed: ${t.message}")
        }
    }

    fun resume() {
        if (!paused || !started) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                recorder?.resume()
            }
            paused = false
            RecorderStateBus.publishPhase(RecorderStateBus.Phase.RECORDING)
            onState(State.RECORDING)
        } catch (t: Throwable) {
            Log.w(TAG, "resume failed: ${t.message}")
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        onState(State.STOPPING)

        // MediaRecorder.stop() throws if it was never given any frames. Guard
        // it so we still finalise and publish whatever was captured.
        val rec = recorder
        if (rec != null && started) {
            try {
                rec.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "recorder.stop failed (too short / no frames?): ${t.message}")
                // The output file may be unusable; mark it so the service
                // doesn't try to publish a corrupt clip.
                workingFile?.let { if (it.length() < 1024) workingFile = null }
            }
        }

        releaseAll()
        RecorderStateBus.reset()
        onState(State.STOPPED)
        engineScope.cancel()
    }

    // ---------------------------------------------------------------------
    // MediaRecorder configuration
    // ---------------------------------------------------------------------
    private data class Attempt(val useHevc: Boolean, val mic: Boolean, val fps: Int)

    private fun configureRecorder(out: File, micAudio: Boolean, useHevc: Boolean, fps: Int): Boolean {
        // Each attempt needs a fresh recorder instance.
        runCatching { recorder?.release() }
        recorder = null
        inputSurface = null

        val rec = newRecorder()
        return try {
            // ORDER MATTERS for MediaRecorder.
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (micAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)

            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(out.absolutePath)

            rec.setVideoEncoder(
                if (useHevc) MediaRecorder.VideoEncoder.HEVC
                else MediaRecorder.VideoEncoder.H264
            )
            if (micAudio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            rec.setVideoSize(encWidth, encHeight)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(cfg.bitrateBps.coerceIn(1_000_000, 100_000_000))
            if (micAudio) {
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setOrientationHint(0)

            rec.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
            }

            rec.prepare()
            inputSurface = rec.surface
            recorder = rec
            Log.i(TAG, "MediaRecorder prepared (hevc=$useHevc, mic=$micAudio, fps=$fps)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "configureRecorder failed (hevc=$useHevc, mic=$micAudio, fps=$fps): ${t.message}")
            runCatching { rec.release() }
            recorder = null
            inputSurface = null
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else MediaRecorder()

    private fun configureVirtualDisplay() {
        val surface = inputSurface ?: throw IllegalStateException("recorder surface missing")
        virtualDisplay = projection.createVirtualDisplay(
            "RecorderZy",
            encWidth,
            encHeight,
            cfg.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private fun audioWantsMic(): Boolean =
        cfg.audioMode == RecorderConfig.AudioMode.MIC ||
            cfg.audioMode == RecorderConfig.AudioMode.BOTH

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun align16(value: Int): Int = (value / 16) * 16

    private suspend fun tickElapsed() {
        val startedAt = nowMs()
        var pausedSince = -1L
        var pausedAccum = 0L
        while (engineScope.isActive) {
            delay(250)
            if (paused) {
                if (pausedSince < 0L) pausedSince = nowMs()
            } else if (pausedSince >= 0L) {
                pausedAccum += nowMs() - pausedSince
                pausedSince = -1L
            }
            val livePause = if (pausedSince >= 0L) nowMs() - pausedSince else 0L
            val ms = (nowMs() - startedAt - pausedAccum - livePause).coerceAtLeast(0L)
            RecorderStateBus.publishElapsed(ms)
        }
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    private fun releaseAll() {
        runCatching { virtualDisplay?.release() }
        runCatching {
            recorder?.let {
                it.reset()
                it.release()
            }
        }
        runCatching { inputSurface?.release() }
        runCatching { projection.stop() }
        virtualDisplay = null
        recorder = null
        inputSurface = null
        started = false
    }

    companion object {
        private const val TAG = "ScreenRecorderEngine"
    }
}
