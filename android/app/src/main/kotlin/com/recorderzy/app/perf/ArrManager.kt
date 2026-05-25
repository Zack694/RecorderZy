package com.recorderzy.app.perf

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * Adaptive Refresh Rate (ARR) helper.
 *
 * Android 16 introduced public APIs for the recorder to *match* the panel's
 * adaptive frame-rate behaviour, so that 120 Hz gameplay on a 120 Hz panel is
 * recorded at exactly 120 fps without tearing or duplicated frames:
 *
 *  - [Display.hasArrSupport] (boolean: does the panel support ARR at all?)
 *  - [Display.getSuggestedFrameRate] (returns the panel's currently-suggested
 *    refresh rate for the supplied frame-rate category).
 *
 * Both are loaded reflectively so source compiles against pre-API-35 toolchains
 * with `compileSdk 36` set – the runtime `Build.VERSION.SDK_INT` guard prevents
 * NoSuchMethodError on older devices.
 */
object ArrManager {

    private const val TAG = "ArrManager"

    fun hasArrSupport(context: Context): Boolean {
        val display = primaryDisplay(context) ?: return false
        if (Build.VERSION.SDK_INT < 35) return false
        return try {
            val m = Display::class.java.getMethod("hasArrSupport")
            (m.invoke(display) as? Boolean) ?: false
        } catch (e: Throwable) {
            Log.d(TAG, "hasArrSupport unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * @param category One of:
     *   0 = NO_PREFERENCE, 1 = LOW, 2 = NORMAL, 3 = HIGH, 4 = HIGH_HINT
     */
    fun suggestedFrameRate(context: Context, category: Int): Float {
        val display = primaryDisplay(context) ?: return 60f
        if (Build.VERSION.SDK_INT < 35) return display.refreshRate
        return try {
            val m = Display::class.java.getMethod("getSuggestedFrameRate", Int::class.javaPrimitiveType)
            (m.invoke(display, category) as? Float) ?: display.refreshRate
        } catch (e: Throwable) {
            Log.d(TAG, "getSuggestedFrameRate unavailable: ${e.javaClass.simpleName}")
            display.refreshRate
        }
    }

    private fun primaryDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return dm?.getDisplay(Display.DEFAULT_DISPLAY)
    }
}
