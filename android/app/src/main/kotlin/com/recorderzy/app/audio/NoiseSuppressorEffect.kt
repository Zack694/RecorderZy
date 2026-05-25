package com.recorderzy.app.audio

import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Wrapper around the device's hardware [NoiseSuppressor] effect chain. When
 * available (most modern Android phones expose it through Qualcomm/MediaTek
 * audio HALs), this is functionally equivalent to running RNNoise / WebRTC NS
 * at zero CPU cost on the application thread.
 *
 * If the device does not expose a hardware NoiseSuppressor, callers are
 * expected to fall back to a software path — slot for a future RNNoise JNI
 * binding (`librnnoise.so`) is documented in the README.
 */
class NoiseSuppressorEffect private constructor(private val effect: NoiseSuppressor) {

    fun release() {
        try { effect.enabled = false } catch (_: Throwable) {}
        try { effect.release() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "NoiseSuppressorEffect"

        fun tryAttach(audioSessionId: Int): NoiseSuppressorEffect? {
            if (audioSessionId == 0) return null
            return try {
                if (!NoiseSuppressor.isAvailable()) {
                    Log.i(TAG, "Hardware NoiseSuppressor not available – software RNNoise fallback recommended.")
                    return null
                }
                val effect = NoiseSuppressor.create(audioSessionId)
                effect?.enabled = true
                effect?.let { NoiseSuppressorEffect(it) }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to attach NoiseSuppressor", e)
                null
            }
        }
    }
}
