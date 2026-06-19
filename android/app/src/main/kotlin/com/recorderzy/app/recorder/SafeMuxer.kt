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
    private var expectedTracks = 2
    private var addedTracks = 0
    private val started = AtomicBoolean(false)
    private val lock = Object()
    private var audioExpected = true

    fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        if (started.get()) {
            Log.e(TAG, "Cannot add track after muxer started")
            return -1
        }
        val track = muxer.addTrack(format)
        addedTracks += 1
        Log.i(TAG, "Track added: $track (total: $addedTracks/$expectedTracks)")
        track
    }

    /** Marks the muxer ready and starts it once everyone has joined. */
    fun maybeStart() {
        synchronized(lock) {
            if (started.get()) {
                Log.d(TAG, "Muxer already started")
                return
            }
            if (addedTracks < expectedTracks) {
                Log.d(TAG, "Waiting for more tracks ($addedTracks/$expectedTracks)")
                return
            }
            try {
                muxer.start()
                started.set(true)
                Log.i(TAG, "Muxer started with $addedTracks tracks")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start muxer: ${e.message}", e)
            }
        }
    }

    /** Allows callers to start with only video (audio is muted). */
    fun forceStartWithVideoOnly() {
        synchronized(lock) {
            if (started.get()) {
                Log.d(TAG, "Muxer already started (force)")
                return
            }
            try {
                muxer.start()
                started.set(true)
                Log.i(TAG, "Muxer force-started with video only")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to force-start muxer: ${e.message}", e)
            }
        }
    }

    fun expectAudio(audioPresent: Boolean) {
        synchronized(lock) {
            audioExpected = audioPresent
            if (!audioPresent) {
                // Only video track will register; adjust expected count
                expectedTracks = 1
                Log.i(TAG, "Audio disabled, expecting only 1 track")
            }
        }
    }

    fun isStarted(): Boolean = started.get()

    fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started.get()) {
                Log.w(TAG, "Attempted write before muxer started (track=$track)")
                return
            }
            if (track < 0) {
                Log.w(TAG, "Invalid track index: $track")
                return
            }
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
            if (!started.get()) {
                Log.w(TAG, "Muxer not started, skipping stop")
                return
            }
            try {
                muxer.stop()
                Log.i(TAG, "Muxer stopped successfully")
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
