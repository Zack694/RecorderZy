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

    // Encoder dimensions actually used. These may differ from the requested
    // cfg.widthPx/heightPx because hardware encoders require the width/height
    // to be aligned (commonly to a multiple of 16). The VirtualDisplay must
    // use the SAME dimensions as the encoder input surface, so we compute
    // these once in configureVideoEncoder and reuse them.
    private var encWidth = 0
    private var encHeight = 0

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

            // Validate cache directory exists and is writable
            val cacheDir = context.cacheDir
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IllegalStateException("Cache directory unavailable")
            }

            // Cache file we'll later move into the public MediaStore album.
            val out = File(cacheDir, "recorderzy_${System.currentTimeMillis()}.mp4")
            workingFile = out
            muxer = SafeMuxer(out.absolutePath)

            configureVideoEncoder()
            configureVirtualDisplay()
            startAudioPipeline()
            
            // Verify muxer is ready before starting drain
            if (muxer == null) {
                throw IllegalStateException("Muxer initialization failed")
            }
            
            startEncoderDrain()

            adpf.start(engineScope)
            engineScope.launch { observeAdpf() }
            engineScope.launch { tickElapsed() }

            // Only publish RECORDING phase after the pipeline is fully up.
            // If we'd published it earlier the overlay would show "recording"
            // while the encoder was still starting and a config failure would
            // leave the bus in an inconsistent RECORDING state.
            RecorderStateBus.publishPhase(RecorderStateBus.Phase.RECORDING)
            onState(State.RECORDING)
            Log.i(TAG, "Recording engine started successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start engine: ${t.message}", t)
            RecorderStateBus.reset()
            onState(State.ERROR)
            releaseAll()
            throw t // Re-throw so service can handle it
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
        val reqWidth = cfg.widthPx
        val reqHeight = cfg.heightPx

        // Validate dimensions
        if (reqWidth <= 0 || reqHeight <= 0) {
            throw IllegalArgumentException("Invalid video dimensions: ${reqWidth}x${reqHeight}")
        }

        // Determine codec priority: APV > HEVC > H.264 (fallback)
        val mimePreferences = buildList {
            if (cfg.useApv && supportsApv()) add(APV_MIME)
            add(MediaFormat.MIMETYPE_VIDEO_HEVC)
            add(MediaFormat.MIMETYPE_VIDEO_AVC) // always-available fallback
        }

        var lastError: Throwable? = null
        for (mime in mimePreferences) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createEncoderByType(mime)

                // Query what this specific encoder actually supports and snap
                // the requested size to its alignment + bounds. This is the
                // fix for devices like the Poco X6 5G (1220x2712) whose native
                // resolution is NOT a multiple of 16 - the unaligned size made
                // configure()/createInputSurface() fail and recording died
                // immediately after the user granted the projection consent.
                val caps = codec.codecInfo
                    .getCapabilitiesForType(mime)
                    .videoCapabilities

                val wAlign = caps.widthAlignment.coerceAtLeast(2)
                val hAlign = caps.heightAlignment.coerceAtLeast(2)

                var aw = (reqWidth / wAlign) * wAlign
                var ah = (reqHeight / hAlign) * hAlign
                aw = aw.coerceIn(caps.supportedWidths.lower, caps.supportedWidths.upper)
                ah = ah.coerceIn(caps.supportedHeights.lower, caps.supportedHeights.upper)
                // Re-align after clamping to the supported range.
                aw = (aw / wAlign) * wAlign
                ah = (ah / hAlign) * hAlign

                if (!caps.isSizeSupported(aw, ah)) {
                    throw IllegalStateException(
                        "Encoder $mime does not support ${aw}x${ah} (requested ${reqWidth}x${reqHeight})"
                    )
                }

                // Clamp fps and bitrate to what the encoder allows at this size.
                val baseFps = if (arr.hasArrSupport()) {
                    arr.suggestedFrameRate(cfg.frameRate)
                } else {
                    cfg.frameRate
                }
                val maxFps = runCatching {
                    caps.getSupportedFrameRatesFor(aw, ah).upper.toInt()
                }.getOrDefault(baseFps)
                val fps = baseFps.coerceIn(1, maxFps.coerceAtLeast(1))

                val bitrate = runCatching {
                    cfg.bitrateBps.coerceIn(
                        caps.bitrateRange.lower,
                        caps.bitrateRange.upper
                    )
                }.getOrDefault(cfg.bitrateBps)

                val format = MediaFormat.createVideoFormat(mime, aw, ah).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    // Don't force profile/level - let the encoder pick what
                    // it actually supports. Forcing HEVCMainTierLevel51 was
                    // crashing on Dimensity/Snapdragon mid-range chips.
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                    ?: throw IllegalStateException("createInputSurface returned null")
                inputSurface = surface
                codec.start()
                videoCodec = codec
                encWidth = aw
                encHeight = ah
                Log.i(TAG, "Video encoder configured: $mime @ ${aw}x${ah} ${fps}fps " +
                    "${bitrate}bps (requested ${reqWidth}x${reqHeight})")
                arr.lockSurfaceFrameRate(surface, fps)
                return // success
            } catch (t: Throwable) {
                Log.w(TAG, "Encoder $mime failed, trying next: ${t.message}", t)
                lastError = t
                // Clean up partial initialization
                runCatching { inputSurface?.release() }
                inputSurface = null
                runCatching { codec?.release() }
                videoCodec = null
            }
        }
        // If all codecs failed, throw so the caller (start()) catches it
        throw IllegalStateException("All video encoders failed. Last error: ${lastError?.message}", lastError)
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
        // Use the aligned encoder dimensions, NOT the raw cfg dimensions, so
        // the VirtualDisplay output matches the encoder input surface exactly.
        val w = if (encWidth > 0) encWidth else cfg.widthPx
        val h = if (encHeight > 0) encHeight else cfg.heightPx
        virtualDisplay = projection.createVirtualDisplay(
            "RecorderZy",
            w,
            h,
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
        val codec = videoCodec ?: throw IllegalStateException("Video codec not initialized")
        val mux = muxer ?: throw IllegalStateException("Muxer not initialized")
        encoderDrainJob = engineScope.launch {
            val info = MediaCodec.BufferInfo()
            try {
                Log.i(TAG, "Video encoder drain loop started")
                while (isActive) {
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoStarted) continue
                            val format = codec.outputFormat
                            Log.i(TAG, "Video encoder format ready: $format")
                            videoTrackIndex = mux.addTrack(format)
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
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.i(TAG, "Video encoder reached EOS")
                                break
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Encoder drain stopped with error: ${t.message}", t)
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
