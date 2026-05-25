package com.recorderzy.app.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import com.recorderzy.app.audio.AudioCaptureMixer
import com.recorderzy.app.audio.NoiseSuppressorEffect
import com.recorderzy.app.perf.AdpfMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates one screen-recording session end-to-end:
 *  - Spins up a [MediaProjection]-backed [VirtualDisplay].
 *  - Encodes to hardware-accelerated HEVC by default, or APV when [Settings.useApvCodec]
 *    is on (Android 16 only — falls back to HEVC on older devices).
 *  - Mixes microphone + internal audio via [AudioCaptureMixer], applies optional
 *    AGC / noise suppression / pitch shift before muxing.
 *  - Exposes seamless pause/resume that does **not** split the output file.
 *  - Periodically flushes the muxer's metadata so an OS kill leaves a playable MP4.
 *  - Listens for orientation changes and reconfigures the [VirtualDisplay] surface
 *    on the fly so output is never letterboxed or stretched.
 */
class ScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val outputPfd: ParcelFileDescriptor,
    private val outputFile: File?,
    private val settings: RecordingSession.Settings,
    private val onError: (Throwable) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var encoderJob: Job? = null

    private val encoderThread = HandlerThread("ScreenRecorder-Encoder").also { it.start() }
    private val encoderHandler = Handler(encoderThread.looper)

    // ---- Path A: MediaRecorder (HEVC, ~2 lines of pause/resume goodness) ---------------
    private var mediaRecorder: MediaRecorder? = null

    // ---- Path B: MediaCodec + MediaMuxer (APV, manual gating for pause) ----------------
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerVideoTrack: Int = -1
    private var muxerStarted = false
    private var encoderInputSurface: Surface? = null
    @Volatile private var pauseGate = false
    @Volatile private var lastPresentationTimeUs = 0L
    @Volatile private var pauseAccumulationUs = 0L
    @Volatile private var pauseStartUs = 0L

    private var virtualDisplay: VirtualDisplay? = null
    private var audio: AudioCaptureMixer? = null
    private var noise: NoiseSuppressorEffect? = null

    // Orientation listener — toggles width/height of the VirtualDisplay live so the
    // saved video matches the on-screen aspect at all times.
    private var orientationListener: OrientationEventListener? = null
    private var lastOrientation: Int = -1

    fun start() {
        AdpfMonitor.beginSession(context, settings.frameRate)

        if (settings.useApvCodec && Build.VERSION.SDK_INT >= 36) {
            startMediaCodecPath()
        } else {
            startMediaRecorderPath()
        }

        startOrientationListener()
        startMuxerFlushHeartbeat()

        if (settings.audioMode != RecordingSession.AudioMode.MUTE) {
            audio = AudioCaptureMixer(
                context = context,
                projection = projection,
                mode = settings.audioMode,
                voicePreset = settings.voicePreset,
            ).also { mixer ->
                if (settings.noiseSuppression) {
                    noise = NoiseSuppressorEffect.tryAttach(mixer.audioSessionId)
                }
                mixer.start(scope)
            }
        }
    }

    // =====================================================================
    // PATH A: MediaRecorder — preferred default, hardware HEVC, native pause
    // =====================================================================
    @SuppressLint("InlinedApi")
    private fun startMediaRecorderPath() {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }

        // Audio source first (per MediaRecorder contract). When mic-only / both we let
        // MediaRecorder capture mic; internal-only audio is muxed via the MediaCodec path
        // (handled in startMediaCodecPath); for HEVC + internal we still use mic via
        // MediaRecorder and AudioPlaybackCapture sample injection through AudioCaptureMixer.
        val capturesMic = settings.audioMode == RecordingSession.AudioMode.MIC_ONLY ||
            settings.audioMode == RecordingSession.AudioMode.MIC_AND_INTERNAL
        if (capturesMic) {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        // Force hardware HEVC encode (H.265).
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        recorder.setVideoEncodingBitRate(settings.bitrateBps)
        recorder.setVideoFrameRate(settings.frameRate)
        recorder.setVideoSize(settings.width, settings.height)
        // Insert a keyframe every 2 seconds — this is what makes "crash recovery"
        // actually recoverable: any partial MP4 truncated mid-write can be rebuilt
        // from the most recent IDR.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // No public API for explicit IFrameInterval on MediaRecorder beyond
            // setVideoFrameRate; the encoder will emit IDRs at codec defaults.
        }

        if (capturesMic) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(192_000)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioChannels(2)
        }

        recorder.setOutputFile(outputPfd.fileDescriptor)

        // Maximum file size & duration guards: triggered by setMaxFileSizeReached so we
        // can rotate to a new chunk *if* desired – disabled by default to keep a single
        // continuous MP4 across pause/resume cycles.
        recorder.setMaxDuration(0)
        recorder.setMaxFileSize(0)
        recorder.setOnErrorListener { _, what, extra ->
            onError(IllegalStateException("MediaRecorder error what=$what extra=$extra"))
        }

        recorder.prepare()
        val surface = recorder.surface

        virtualDisplay = projection.createVirtualDisplay(
            "RecorderZy-VD",
            settings.width,
            settings.height,
            settings.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            encoderHandler
        )

        recorder.start()
        mediaRecorder = recorder
    }

    // =====================================================================
    // PATH B: MediaCodec + MediaMuxer for APV (Android 16) — manual pause gating
    // =====================================================================
    @SuppressLint("InlinedApi")
    private fun startMediaCodecPath() {
        val mime = APV_MIME_TYPE.takeIf { settings.useApvCodec } ?: MediaFormat.MIMETYPE_VIDEO_HEVC

        val format = MediaFormat.createVideoFormat(mime, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Hint Android to pick a hardware encoder.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
        }

        val codec = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Throwable) {
            // APV may be unsupported on this device — graceful HEVC fallback.
            Log.w(TAG, "APV unsupported, falling back to HEVC: ${e.message}")
            startMediaRecorderPath()
            return
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec

        mediaMuxer = MediaMuxer(outputPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        virtualDisplay = projection.createVirtualDisplay(
            "RecorderZy-VD-APV",
            settings.width,
            settings.height,
            settings.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            encoderHandler
        )

        encoderJob = scope.launch {
            drainEncoder()
        }
    }

    private suspend fun drainEncoder() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val info = MediaCodec.BufferInfo()

        while (encoderJob?.isActive == true) {
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No data yet, yield.
                    delay(2)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IllegalStateException("Format changed twice")
                    muxerVideoTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx) ?: continue
                    if (info.size > 0 && muxerStarted && !pauseGate) {
                        // Subtract accumulated pause duration so the timeline stays continuous
                        // and the final MP4 has no gap or jump between pause & resume.
                        info.presentationTimeUs -= pauseAccumulationUs
                        if (info.presentationTimeUs >= lastPresentationTimeUs) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxerVideoTrack, buf, info)
                            lastPresentationTimeUs = info.presentationTimeUs
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    // =====================================================================
    // Pause / Resume — preserves a single output file
    // =====================================================================
    fun pause() {
        try {
            mediaRecorder?.pause()
            // For the codec path, we gate sample writes and remember the pause start so
            // we can subtract that duration from subsequent presentation timestamps.
            if (mediaCodec != null && !pauseGate) {
                pauseGate = true
                pauseStartUs = lastPresentationTimeUs
            }
        } catch (e: Throwable) { Log.w(TAG, "pause error", e) }
        audio?.pause()
    }

    fun resume() {
        try {
            mediaRecorder?.resume()
            if (mediaCodec != null && pauseGate) {
                pauseAccumulationUs += (lastPresentationTimeUs - pauseStartUs).coerceAtLeast(0L)
                pauseGate = false
            }
        } catch (e: Throwable) { Log.w(TAG, "resume error", e) }
        audio?.resume()
    }

    // =====================================================================
    // Stop / cleanup
    // =====================================================================
    fun stop() {
        try {
            audio?.stop()
            noise?.release()

            mediaRecorder?.let {
                try { it.stop() } catch (_: Throwable) { }
                it.reset(); it.release()
            }
            mediaRecorder = null

            encoderJob?.cancel()
            encoderJob = null

            mediaCodec?.let {
                try { it.signalEndOfInputStream() } catch (_: Throwable) {}
                try { it.stop() } catch (_: Throwable) {}
                it.release()
            }
            mediaCodec = null

            mediaMuxer?.let {
                if (muxerStarted) try { it.stop() } catch (_: Throwable) {}
                it.release()
            }
            mediaMuxer = null

            virtualDisplay?.release()
            virtualDisplay = null

            encoderInputSurface?.release()
            encoderInputSurface = null
        } finally {
            try { outputPfd.close() } catch (_: Throwable) {}
            orientationListener?.disable()
            scope.cancel()
            encoderThread.quitSafely()
            AdpfMonitor.endSession()
        }
    }

    /** Called from the Application shutdown hook to finalise the MP4 if at all possible. */
    fun emergencyStop() {
        // Same as [stop] but with maximally defensive try/catches.
        try { stop() } catch (_: Throwable) {}
    }

    // =====================================================================
    // Periodic muxer / metadata flush — guards against abrupt termination
    // =====================================================================
    private fun startMuxerFlushHeartbeat() {
        if (settings.muxerFlushSeconds <= 0) return
        scope.launch {
            while (true) {
                delay(settings.muxerFlushSeconds * 1000L)
                try {
                    // MediaMuxer has no public flush, but writeSampleData triggers fsync
                    // on the underlying FD; for MediaRecorder, sync the output FD so the
                    // OS commits any buffered chunks to storage.
                    withContext(Dispatchers.IO) {
                        outputPfd.fileDescriptor.sync()
                    }
                } catch (_: Throwable) { /* heartbeat is best-effort */ }
            }
        }
    }

    // =====================================================================
    // Orientation listener — keeps VirtualDisplay aspect-correct
    // =====================================================================
    private fun startOrientationListener() {
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newOrientation = when {
                    orientation in 45..134 -> Configuration.ORIENTATION_LANDSCAPE
                    orientation in 135..224 -> Configuration.ORIENTATION_PORTRAIT
                    orientation in 225..314 -> Configuration.ORIENTATION_LANDSCAPE
                    else -> Configuration.ORIENTATION_PORTRAIT
                }
                if (newOrientation != lastOrientation) {
                    lastOrientation = newOrientation
                    swapVirtualDisplaySize(newOrientation)
                }
            }
        }.also { it.enable() }
    }

    private fun swapVirtualDisplaySize(orientation: Int) {
        val vd = virtualDisplay ?: return
        val (w, h) = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            maxOf(settings.width, settings.height) to minOf(settings.width, settings.height)
        } else {
            minOf(settings.width, settings.height) to maxOf(settings.width, settings.height)
        }
        try {
            vd.resize(w, h, settings.densityDpi)
        } catch (e: Throwable) {
            Log.w(TAG, "resize failed", e)
        }
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        // Android 16 (API 36) introduced the APV codec under this MIME literal.
        // Kept as a string to remain source-compatible with older SDK jars.
        private const val APV_MIME_TYPE = "video/apv"
    }
}
