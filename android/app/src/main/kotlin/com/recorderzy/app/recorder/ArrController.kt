package com.recorderzy.app.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * Wraps Android 16's Adaptive Refresh Rate (ARR) APIs:
 *  - `Display.hasArrSupport()` to detect whether the panel can advertise
 *    a dynamic frame rate ladder.
 *  - `Display.getSuggestedFrameRate(category)` to request the system's
 *    recommended FPS for a given content category.
 *
 * On older releases all calls collapse into safe defaults so the recorder
 * still behaves on pre-Baklava devices.
 */
class ArrController(private val context: Context) {

    private val displayManager: DisplayManager? =
        context.getSystemService(DisplayManager::class.java)

    fun hasArrSupport(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val display = primaryDisplay() ?: return false
        return runCatching {
            val method = display.javaClass.methods
                .firstOrNull { it.name == "hasArrSupport" && it.parameterCount == 0 }
            (method?.invoke(display) as? Boolean) == true
        }.getOrDefault(false)
    }

    /**
     * Asks Android 16 for a suggested frame rate, defaulting to the user's
     * configured FPS when ARR isn't available.
     */
    fun suggestedFrameRate(fallback: Int): Int {
        if (Build.VERSION.SDK_INT < 36) return fallback
        val display = primaryDisplay() ?: return fallback
        return runCatching {
            val method = display.javaClass.methods
                .firstOrNull { it.name == "getSuggestedFrameRate" }
            val raw = method?.invoke(
                display,
                /* category= */ FRAME_RATE_CATEGORY_HIGH
            ) as? Number
            raw?.toInt()?.takeIf { it in 24..240 } ?: fallback
        }.getOrDefault(fallback)
    }

    /**
     * Locks the recording surface to a target frame rate so the encoder, the
     * VirtualDisplay refresh and the panel pace itself all line up.
     */
    fun lockSurfaceFrameRate(surface: Surface, fps: Int) {
        if (fps <= 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    fps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Surface.setFrameRate failed: ${t.message}")
        }
    }

    private fun primaryDisplay(): Display? =
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

    companion object {
        private const val TAG = "ArrController"

        // android.view.Display.FRAME_RATE_CATEGORY_HIGH is API 36.
        // We hard-code the constant to avoid a soft-fail on devices that have
        // the API surface stubbed.
        private const val FRAME_RATE_CATEGORY_HIGH = 4
    }
}
