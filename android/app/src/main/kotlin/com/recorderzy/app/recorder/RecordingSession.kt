package com.recorderzy.app.recorder

import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide singleton holding the in-flight recording state. Exists so that
 * the [com.recorderzy.app.service.ScreenRecordService] foreground service, the
 * notification action receivers, the floating bubble overlay service, and the
 * Flutter UI all observe the same source of truth without round-tripping
 * through Binder for trivial state queries.
 */
class RecordingSession private constructor(
    val settings: Settings,
) {

    @Volatile var isActive: Boolean = false
        internal set

    @Volatile var isPaused: Boolean = false
        internal set

    @Volatile var startedAtNanos: Long = 0L
        internal set

    @Volatile var pausedDurationMs: Long = 0L
        internal set

    @Volatile var outputUri: Uri? = null
        internal set

    @Volatile internal var recorder: ScreenRecorder? = null

    /** Snapshot of the current elapsed (un-paused) recording duration in ms. */
    fun elapsedMs(): Long {
        if (!isActive) return 0L
        val now = System.nanoTime()
        val raw = (now - startedAtNanos) / 1_000_000L
        return (raw - pausedDurationMs).coerceAtLeast(0L)
    }

    /**
     * Best-effort emergency stop; called from [com.recorderzy.app.RecorderApp]'s
     * shutdown hook so abrupt JVM termination still produces a playable MP4.
     */
    fun emergencyFinalise() {
        try { recorder?.emergencyStop() } catch (_: Throwable) { /* swallow */ }
        isActive = false
    }

    data class Settings(
        /** Pixels per second. Recorder enforces this on the encoder bitrate. */
        val bitrateBps: Int,
        val frameRate: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val useApvCodec: Boolean,
        val audioMode: AudioMode,
        val noiseSuppression: Boolean,
        val voicePreset: VoicePreset,
        val showTouches: Boolean,
        val muxerFlushSeconds: Int,
    ) {
        companion object {
            val DEFAULT = Settings(
                bitrateBps = 12_000_000,
                frameRate = 60,
                width = 1080,
                height = 1920,
                densityDpi = 420,
                useApvCodec = false,
                audioMode = AudioMode.MIC_AND_INTERNAL,
                noiseSuppression = true,
                voicePreset = VoicePreset.NORMAL,
                showTouches = false,
                muxerFlushSeconds = 2,
            )

            fun fromMap(map: Map<*, *>?): Settings {
                if (map == null) return DEFAULT
                fun int(key: String, default: Int) = (map[key] as? Number)?.toInt() ?: default
                fun bool(key: String, default: Boolean) = map[key] as? Boolean ?: default
                fun str(key: String, default: String) = map[key] as? String ?: default
                return Settings(
                    bitrateBps = int("bitrateBps", DEFAULT.bitrateBps),
                    frameRate = int("frameRate", DEFAULT.frameRate),
                    width = int("width", DEFAULT.width),
                    height = int("height", DEFAULT.height),
                    densityDpi = int("densityDpi", DEFAULT.densityDpi),
                    useApvCodec = bool("useApvCodec", DEFAULT.useApvCodec),
                    audioMode = AudioMode.parse(str("audioMode", DEFAULT.audioMode.id)),
                    noiseSuppression = bool("noiseSuppression", DEFAULT.noiseSuppression),
                    voicePreset = VoicePreset.parse(str("voicePreset", DEFAULT.voicePreset.id)),
                    showTouches = bool("showTouches", DEFAULT.showTouches),
                    muxerFlushSeconds = int("muxerFlushSeconds", DEFAULT.muxerFlushSeconds),
                )
            }
        }
    }

    enum class AudioMode(val id: String) {
        MUTE("mute"),
        MIC_ONLY("mic_only"),
        INTERNAL_ONLY("internal_only"),
        MIC_AND_INTERNAL("mic_and_internal");
        companion object {
            fun parse(id: String) = values().firstOrNull { it.id == id } ?: MIC_AND_INTERNAL
        }
    }

    enum class VoicePreset(val id: String, val pitchSemitones: Int) {
        NORMAL("normal", 0),
        DEEP("deep", -5),
        ROBOTIC("robotic", 0),
        HELIUM("helium", 7),
        RADIO("radio", 0);
        companion object {
            fun parse(id: String) = values().firstOrNull { it.id == id } ?: NORMAL
        }
    }

    companion object {
        private val ref = AtomicReference<RecordingSession?>(null)

        /** Settings staged by [MethodChannels.handleRecorder] before the projection token arrives. */
        @Volatile var pendingSettings: Settings = Settings.DEFAULT

        val instance: RecordingSession?
            get() = ref.get()

        fun create(settings: Settings): RecordingSession {
            val s = RecordingSession(settings)
            ref.set(s)
            return s
        }

        fun clear() {
            ref.set(null)
        }
    }
}
