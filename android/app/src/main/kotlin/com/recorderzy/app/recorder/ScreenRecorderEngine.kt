package com.recorderzy.app.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
 * [VirtualDisplay] - the same high-level approach mainstream recorders
 * (AZ Screen Recorder, XRecorder) use. MediaRecorder owns the encoder, the
 * MP4 muxer, mic audio and A/V sync, which is far more device-compatible
 * than a hand-rolled MediaCodec/MediaMuxer pipeline.
 *
 * Key robustness step: the requested size is clamped to what the device
 * encoder actually supports (alignment + max dimensions). Tall portrait
 * sizes such as the Poco X6 5G's 1220x2712 commonly exceed an encoder's max
 * height (encoders are built for landscape), which made even H.264 fail.
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

    /** Human-readable reason the last start failed (surfaced to the user). */
    @Volatile var lastError: String? = null
        private set

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

            val micEnabled = audioWantsMic() && hasRecordAudioPermission()
            val fps = cfg.frameRate.coerceIn(15, 120)

            // Progressively safer configs until one prepares.
            val attempts = listOf(
                Attempt(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaRecorder.VideoEncoder.HEVC, micEnabled, fps),
                Attempt(MediaFormat.MIMETYPE_VIDEO_AVC, MediaRecorder.VideoEncoder.H264, micEnabled, fps),
                Attempt(MediaFormat.MIMETYPE_VIDEO_AVC, MediaRecorder.VideoEncoder.H264, micEnabled, 30),
                Attempt(MediaFormat.MIMETYPE_VIDEO_AVC, MediaRecorder.VideoEncoder.H264, false, 30),
            )

            var prepared = false
            for (a in attempts) {
                if (configureRecorder(out, a)) { prepared = true; break }
            }
            if (!prepared) {
                throw IllegalStateException(lastError ?: "MediaRecorder could not be prepared")
            }

            configureVirtualDisplay()
            recorder?.start()
            started = true

            engineScope.launch { tickElapsed() }

            RecorderStateBus.publishPhase(RecorderStateBus.Phase.RECORDING)
            onState(State.RECORDING)
            Log.i(TAG, "Recording started @ ${encWidth}x${encHeight} mic=$micEnabled")
        } catch (t: Throwable) {
            if (lastError == null) lastError = "${t.javaClass.simpleName}: ${t.message}"
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
            recorder?.pause()
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
            recorder?.resume()
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

        val rec = recorder
        if (rec != null && started) {
            try {
                rec.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "recorder.stop failed (too short / no frames?): ${t.message}")
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
    private data class Attempt(
        val mime: String,
        val encoder: Int,
        val mic: Boolean,
        val fps: Int,
    )

    private fun configureRecorder(out: File, a: Attempt): Boolean {
        runCatching { recorder?.release() }
        recorder = null
        inputSurface = null

        // Clamp the requested size to what THIS encoder supports.
        val (w, h) = supportedSize(a.mime, cfg.widthPx, cfg.heightPx)
        if (w < 16 || h < 16) {
            lastError = "Encoder ${a.mime} reports no usable size for ${cfg.widthPx}x${cfg.heightPx}"
            return false
        }

        val rec = newRecorder()
        return try {
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (a.mic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)

            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(out.absolutePath)

            rec.setVideoEncoder(a.encoder)
            if (a.mic) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(a.fps)
            rec.setVideoEncodingBitRate(cfg.bitrateBps.coerceIn(1_000_000, 100_000_000))
            if (a.mic) {
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setOrientationHint(0)
            rec.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder runtime error what=$what extra=$extra")
            }

            rec.prepare()
            inputSurface = rec.surface
            recorder = rec
            encWidth = w
            encHeight = h
            Log.i(TAG, "MediaRecorder prepared: ${a.mime} ${w}x${h} ${a.fps}fps mic=${a.mic}")
            true
        } catch (t: Throwable) {
            lastError = "${a.mime} ${w}x${h}: ${t.javaClass.simpleName} ${t.message}"
            Log.w(TAG, "configureRecorder failed ($lastError)")
            runCatching { rec.release() }
            recorder = null
            inputSurface = null
            false
        }
    }

    /**
     * Returns a (width, height) supported by an encoder for [mime]: aligned
     * to the encoder's alignment, clamped to its max dimensions, and scaled
     * down preserving aspect ratio if the exact size isn't supported.
     */
    private fun supportedSize(mime: String, reqW: Int, reqH: Int): Pair<Int, Int> {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val info = list.codecInfos.firstOrNull { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            } ?: return alignDown(reqW, 16) to alignDown(reqH, 16)

            val caps = info.getCapabilitiesForType(mime).videoCapabilities
            val wa = caps.widthAlignment.coerceAtLeast(2)
            val ha = caps.heightAlignment.coerceAtLeast(2)

            var w = alignDown(reqW.coerceIn(caps.supportedWidths.lower, caps.supportedWidths.upper), wa)
            var h = alignDown(reqH.coerceIn(caps.supportedHeights.lower, caps.supportedHeights.upper), ha)

            if (!caps.isSizeSupported(w, h)) {
                // Scale down preserving aspect ratio until a supported size.
                var scale = 1.0
                var tries = 0
                while (tries < 16) {
                    scale *= 0.9
                    var nw = alignDown((reqW * scale).toInt(), wa)
                        .coerceIn(caps.supportedWidths.lower, caps.supportedWidths.upper)
                    var nh = alignDown((reqH * scale).toInt(), ha)
                        .coerceIn(caps.supportedHeights.lower, caps.supportedHeights.upper)
                    nw = alignDown(nw, wa)
                    nh = alignDown(nh, ha)
                    if (caps.isSizeSupported(nw, nh)) { w = nw; h = nh; break }
                    tries++
                }
            }
            w to h
        } catch (t: Throwable) {
            Log.w(TAG, "supportedSize($mime) failed: ${t.message}")
            alignDown(reqW, 16) to alignDown(reqH, 16)
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

    private fun alignDown(value: Int, align: Int): Int = (value / align) * align

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
