package com.recorderzy.app.perf

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.util.Log

/**
 * Wraps the **Android Dynamic Performance Framework** (ADPF) APIs:
 *
 *  - [PerformanceHintManager.createHintSession] — proactively tell the kernel
 *    governor that this thread group is rendering at the recording target FPS.
 *  - [PowerManager.getThermalHeadroom] — quick "are we about to throttle?" probe
 *    available since API 30 (always works).
 *  - [android.os.health.SystemHealthManager.getCpuHeadroom] /
 *    [android.os.health.SystemHealthManager.getGpuHeadroom] — fine-grained
 *    headroom probes added in Android 16. Loaded reflectively so older SDKs
 *    in the toolchain still compile cleanly.
 *
 * Returns float values normalised to `[0.0 .. 1.0]` where higher = more headroom
 * (less stressed). A return of `null` means the API is not exposed on the host.
 */
object AdpfMonitor {

    private const val TAG = "AdpfMonitor"

    private var hintSession: PerformanceHintManager.Session? = null
    private var hintTargetNanos: Long = 0L

    fun beginSession(context: Context, targetFrameRate: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val pmgr = context.getSystemService(PerformanceHintManager::class.java) ?: return
            // Target nanos per frame — the kernel governor uses this to schedule CPU
            // frequency / core affinity for the recording thread group.
            hintTargetNanos = (1_000_000_000L / targetFrameRate.coerceAtLeast(30)).toLong()
            val tids = intArrayOf(android.os.Process.myTid())
            hintSession = pmgr.createHintSession(tids, hintTargetNanos)
        } catch (e: Throwable) {
            Log.w(TAG, "createHintSession failed", e)
        }
    }

    fun reportActualWorkDuration(actualNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try { hintSession?.reportActualWorkDuration(actualNanos) }
        catch (_: Throwable) {}
    }

    fun endSession() {
        try { hintSession?.close() } catch (_: Throwable) {}
        hintSession = null
    }

    /**
     * Thermal-headroom heuristic. Returns 1.0 when the device is fully idle and
     * decreases towards 0.0 as the SoC nears its thermal cap. Higher = better.
     */
    fun thermalHeadroom(context: Context): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1f
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 1f
        return try {
            // PowerManager.getThermalHeadroom returns a "fraction of the maximum"; values
            // > 1.0 indicate active throttling. We invert + clamp to 0..1.
            val raw = pm.getThermalHeadroom(/* forecastSeconds = */ 5)
            (1f - raw).coerceIn(0f, 1f)
        } catch (e: Throwable) {
            Log.w(TAG, "getThermalHeadroom failed", e); 1f
        }
    }

    /**
     * CPU headroom via the Android 16 [android.os.health.SystemHealthManager.getCpuHeadroom].
     * Returns null on older devices.
     */
    fun cpuHeadroom(context: Context): Float? {
        return invokeHeadroom(context, "getCpuHeadroom", "CpuHeadroomParams")
    }

    fun gpuHeadroom(context: Context): Float? {
        return invokeHeadroom(context, "getGpuHeadroom", "GpuHeadroomParams")
    }

    private fun invokeHeadroom(context: Context, methodName: String, paramsClassSimpleName: String): Float? {
        if (Build.VERSION.SDK_INT < 35) return null
        return try {
            val shm = context.getSystemService("systemhealth")
                ?: context.getSystemService(Class.forName("android.os.health.SystemHealthManager"))
                ?: return null
            val paramsClass = Class.forName("android.os.health.$paramsClassSimpleName")
            val builderClass = Class.forName("android.os.health.$paramsClassSimpleName\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            val params = builderClass.getMethod("build").invoke(builder)
            val method = shm.javaClass.getMethod(methodName, paramsClass)
            when (val r = method.invoke(shm, params)) {
                is Float -> r.coerceIn(0f, 1f)
                is FloatArray -> r.firstOrNull()?.coerceIn(0f, 1f)
                else -> null
            }
        } catch (e: Throwable) {
            Log.d(TAG, "$methodName not available: ${e.javaClass.simpleName}")
            null
        }
    }
}
