package com.recorderzy.app.recorder

import android.content.Intent

/**
 * Process-static holder for the most recent MediaProjection consent token.
 *
 * Why not stuff the [Intent] into another Intent's extras? On Android 14+ a
 * MediaProjection consent intent carries an [android.os.IBinder] token in its
 * extras. When that intent is parceled into another Intent's extras and then
 * unparcelled in the receiving Service, the IBinder reference can come back
 * stale or null on some OEM kernels (HyperOS / OneUI / ColorOS), which causes
 * `MediaProjectionManager.getMediaProjection()` to throw - and any exception
 * thrown before [android.app.Service.startForeground] is called within the
 * 5-second deadline crashes the whole process with
 * `ForegroundServiceDidNotStartInTimeException`.
 *
 * Holding the token in a static avoids the parcel round-trip entirely; both
 * the projection consent Activity and the recorder Service live in the same
 * process so a plain reference is safe.
 *
 * IMPORTANT: validity is tracked with an explicit [hasToken] flag, NOT by
 * sentinel-checking [resultCode]. `Activity.RESULT_OK` is -1, so using -1 as
 * an "empty" marker would discard every *granted* projection (the exact cause
 * of the "No screen capture permission" failures).
 */
object ProjectionTokenHolder {
    @Volatile private var resultCode: Int = 0
    @Volatile private var data: Intent? = null
    @Volatile private var hasToken: Boolean = false

    fun set(resultCode: Int, data: Intent) {
        synchronized(this) {
            this.resultCode = resultCode
            this.data = data
            this.hasToken = true
        }
    }

    fun take(): Pair<Int, Intent>? {
        synchronized(this) {
            if (!hasToken) return null
            val d = data ?: return null
            val rc = resultCode
            // Single-use: clear after read so a stale token can't cause confusion.
            resultCode = 0
            data = null
            hasToken = false
            return rc to d
        }
    }

    fun clear() {
        synchronized(this) {
            resultCode = 0
            data = null
            hasToken = false
        }
    }
}
