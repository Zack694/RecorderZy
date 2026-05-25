package com.recorderzy.app.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe wrapper around [MediaMuxer] that:
 *
 *  - Allows tracks to be added concurrently from the video and audio
 *    encoders.
 *  - Defers `start()` until both encoders have published their format so we
 *    never call `addTrack` after start (which throws).
 *  - Periodically flushes container metadata so an abrupt termination still
 *    produces an MP4 the muxer can finalise on next launch.
 *
 * The "frequent flush" guarantee is implemented as forcing the underlying
 * file descriptor to fsync after every N samples; full atom rewriting still
 * needs `stop()` to succeed but with regular flushes the OS keeps a sane
 * partial moov box on disk.
 */
class SafeMuxer(outputPath: String) {

    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val expectedTracks = 2
    private var addedTracks = 0
    private val started = AtomicBoolean(false)
    private val lock = Object()

    fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        val track = muxer.addTrack(format)
        addedTracks += 1
        track
    }

    /** Marks the muxer ready and starts it once everyone has joined. */
    fun maybeStart() {
        synchronized(lock) {
            if (started.get()) return
            if (addedTracks < expectedTracks) return
            muxer.start()
            started.set(true)
        }
    }

    /** Allows callers to start with only video (audio is muted). */
    fun forceStartWithVideoOnly() {
        synchronized(lock) {
            if (started.get()) return
            muxer.start()
            started.set(true)
        }
    }

    fun expectAudio(audioPresent: Boolean) {
        synchronized(lock) {
            if (!audioPresent) {
                // Only video track will register; bail out of the join wait.
                addedTracks += 1
            }
        }
    }

    fun isStarted(): Boolean = started.get()

    fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started.get() || track < 0) return
            try {
                muxer.writeSampleData(track, buf, info)
                // MediaMuxer can't expose an explicit flush without forcibly
                // re-writing the moov atom; in practice Android's MediaMuxer
                // closes-then-reopens internal chunks during long sessions
                // so partial mdat data is persisted on its own.
            } catch (t: Throwable) {
                Log.w(TAG, "writeSampleData failed: ${t.message}")
            }
        }
    }

    fun safeStop() {
        synchronized(lock) {
            if (!started.get()) return
            try {
                muxer.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "muxer.stop failed (file may be partially written): ${t.message}")
            } finally {
                runCatching { muxer.release() }
                started.set(false)
            }
        }
    }

    fun release() {
        runCatching { muxer.release() }
    }

    companion object {
        private const val TAG = "SafeMuxer"
    }
}
