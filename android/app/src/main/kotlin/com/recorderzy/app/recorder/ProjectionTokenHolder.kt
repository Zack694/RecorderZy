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
 */
object ProjectionTokenHolder {
    @Volatile var resultCode: Int = -1
    @Volatile var data: Intent? = null

    fun set(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.data = data
    }

    fun take(): Pair<Int, Intent>? {
        val rc = resultCode
        val d = data
        if (rc == -1 || d == null) return null
        // Single-use: clear after read so a stale token can't cause confusion.
        resultCode = -1
        data = null
        return rc to d
    }

    fun clear() {
        resultCode = -1
        data = null
    }
}
