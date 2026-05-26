package com.recorderzy.app.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The top-level recorder engine: stitches together MediaProjection -> a
 * VirtualDisplay -> a MediaCodec encoder (HEVC by default, APV when the
 * device supports it and the user opts in) -> the SafeMuxer that produces
 * an MP4 in app cache. Audio is routed through [AudioPipeline] in parallel.
 *
 * The engine exposes a small state machine ([State]) which is mirrored back
 * to the Flutter layer through `RecorderChannel`. All long-running work is
 * launched on the engine's own scope so the foreground service's main
 * thread never blocks - critical for Android 16's stricter ANR budgets.
 */
class ScreenRecorderEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val cfg: RecorderConfig,
    private val onState: (State) -> Unit,
) {

    enum class State { STARTING, RECORDING, PAUSED, STOPPING, STOPPED, ERROR }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adpf = AdpfMonitor(context)
    private val arr = ArrController(context)

    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: SafeMuxer? = null
    private var videoTrackIndex = -1
    private var videoStarted = false

    private var audioPipeline: AudioPipeline? = null

    private var encoderDrainJob: Job? = null

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    @Volatile private var paused = false
    @Volatile private var stopping = false

    private var workingFile: File? = null
    fun outputFile(): File? = workingFile

    fun start() {
        try {
            onState(State.STARTING)
            RecorderStateBus.publishPhase(RecorderStateBus.Phase.RECORDING)

            // Cache file we'll later move into the public MediaStore album.
            val out = File(context.cacheDir, "recorderzy_${System.currentTimeMillis()}.mp4")
            workingFile = out
            muxer = SafeMuxer(out.absolutePath)

            configureVideoEncoder()
            configureVirtualDisplay()
            startAudioPipeline()
            startEncoderDrain()

            adpf.start(engineScope)
            engineScope.launch { observeAdpf() }
            engineScope.launch { tickElapsed() }

            onState(State.RECORDING)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start engine: ${t.message}", t)
            onState(State.ERROR)
            stop()
        }
    }

    fun pause() {
        if (paused) return
        paused = true
        audioPipeline?.setPaused(true)
        // Detach VirtualDisplay surface so the encoder stops receiving frames.
        // We don't tear the encoder down, so the muxer keeps the same file
        // and the AAC pts continues monotonically when we resume.
        virtualDisplay?.surface = null
        RecorderStateBus.publishPhase(RecorderStateBus.Phase.PAUSED)
        onState(State.PAUSED)
    }

    fun resume() {
        if (!paused) return
        paused = false
        // Reattach the encoder input surface so frames flow again.
        val s = inputSurface
        if (s != null) virtualDisplay?.surface = s
        audioPipeline?.setPaused(false)
        RecorderStateBus.publishPhase(RecorderStateBus.Phase.RECORDING)
        onState(State.RECORDING)
    }

    fun stop() {
        if (stopping) return
        stopping = true
        onState(State.STOPPING)

        try { audioPipeline?.stop() } catch (_: Throwable) {}
        try {
            videoCodec?.let { codec ->
                runCatching {
                    val idx = codec.dequeueInputBuffer(0)
                    if (idx >= 0) codec.queueInputBuffer(
                        idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                runCatching { codec.signalEndOfInputStream() }
            }
        } catch (_: Throwable) {}

        adpf.stop()

        engineScope.launch {
            try {
                // Give the drain loops a moment to flush their EOS samples.
                kotlinx.coroutines.delay(200)
            } finally {
                releaseAll()
                RecorderStateBus.reset()
                onState(State.STOPPED)
                engineScope.cancel()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Video encoder
    // ---------------------------------------------------------------------
    private fun configureVideoEncoder() {
        val width = cfg.widthPx
        val height = cfg.heightPx
        val fps = if (arr.hasArrSupport()) arr.suggestedFrameRate(cfg.frameRate) else cfg.frameRate

        // Determine codec priority: APV > HEVC > H.264 (fallback)
        val mimePreferences = buildList {
            if (cfg.useApv && supportsApv()) add(APV_MIME)
            add(MediaFormat.MIMETYPE_VIDEO_HEVC)
            add(MediaFormat.MIMETYPE_VIDEO_AVC) // always-available fallback
        }

        var lastError: Throwable? = null
        for (mime in mimePreferences) {
            try {
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateBps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    // Don't force profile/level - let the encoder pick what
                    // it actually supports. Forcing HEVCMainTierLevel51 was
                    // crashing on Dimensity/Snapdragon mid-range chips.
                }
                val codec = MediaCodec.createEncoderByType(mime)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                codec.start()
                videoCodec = codec
                Log.i(TAG, "Video encoder configured: $mime @ ${width}x${height} ${fps}fps")
                inputSurface?.let { arr.lockSurfaceFrameRate(it, fps) }
                return // success
            } catch (t: Throwable) {
                Log.w(TAG, "Encoder $mime failed, trying next: ${t.message}")
                lastError = t
            }
        }
        // If all codecs failed, throw so the caller (start()) catches it
        throw IllegalStateException("All video encoders failed", lastError)
    }

    private fun supportsApv(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(APV_MIME, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun configureVirtualDisplay() {
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: throw IllegalStateException("DisplayManager unavailable")
        val surface = inputSurface ?: throw IllegalStateException("encoder surface missing")
        virtualDisplay = projection.createVirtualDisplay(
            "RecorderZy",
            cfg.widthPx,
            cfg.heightPx,
            cfg.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface,
            null,
            null
        )
    }

    private fun startAudioPipeline() {
        val mux = muxer ?: return
        val muted = cfg.audioMode == RecorderConfig.AudioMode.MUTE
        mux.expectAudio(!muted)
        if (muted) return
        val pipeline = AudioPipeline(
            projection = projection,
            muxer = mux,
            mode = cfg.audioMode,
            noiseSuppression = cfg.noiseSuppression,
            voicePreset = cfg.voicePreset,
        )
        audioPipeline = pipeline
        pipeline.start(engineScope)
    }

    private fun startEncoderDrain() {
        val codec = videoCodec ?: return
        val mux = muxer ?: return
        encoderDrainJob = engineScope.launch {
            val info = MediaCodec.BufferInfo()
            try {
                while (isActive) {
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoStarted) continue
                            videoTrackIndex = mux.addTrack(codec.outputFormat)
                            videoStarted = true
                            mux.maybeStart()
                            if (cfg.audioMode == RecorderConfig.AudioMode.MUTE) {
                                mux.forceStartWithVideoOnly()
                            }
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)
                            if (buf != null && info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                if (mux.isStarted()) {
                                    mux.writeSample(videoTrackIndex, buf, info)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Encoder drain stopped: ${t.message}")
            }
        }
    }

    private suspend fun observeAdpf() {
        adpf.headroom.collect { hr ->
            // Aggressively trim frame rate / bitrate when ADPF reports we're
            // running out of thermal budget. This is the difference between
            // a 120 fps gameplay clip with frame drops and one without.
            val health = hr.health()
            if (health < 0.25f) {
                runCatching {
                    videoCodec?.let { codec ->
                        val params = android.os.Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, (cfg.bitrateBps * 0.6).toInt())
                        codec.setParameters(params)
                    }
                }
            } else if (health > 0.6f) {
                runCatching {
                    videoCodec?.let { codec ->
                        val params = android.os.Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, cfg.bitrateBps)
                        codec.setParameters(params)
                    }
                }
            }
        }
    }

    private suspend fun tickElapsed() {
        val started = nowMs()
        var pausedSince = -1L
        var pausedAccum = 0L
        while (engineScope.isActive) {
            kotlinx.coroutines.delay(250)
            if (paused) {
                if (pausedSince < 0L) pausedSince = nowMs()
            } else if (pausedSince >= 0L) {
                pausedAccum += nowMs() - pausedSince
                pausedSince = -1L
            }
            val livePause = if (pausedSince >= 0L) nowMs() - pausedSince else 0L
            val ms = (nowMs() - started - pausedAccum - livePause).coerceAtLeast(0L)
            _elapsedMs.value = ms
            RecorderStateBus.publishElapsed(ms)
        }
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    private fun releaseAll() {
        runCatching { encoderDrainJob?.cancel() }
        runCatching { virtualDisplay?.release() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { muxer?.safeStop() }
        runCatching { projection.stop() }
        virtualDisplay = null
        videoCodec = null
        inputSurface = null
    }

    companion object {
        private const val TAG = "ScreenRecorderEngine"
        const val APV_MIME = "video/apv"
    }
}
