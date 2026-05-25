package com.recorderzy.app.recorder

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the Android Dynamic Performance Framework (ADPF) thermal headroom
 * APIs introduced in Android 16 (API 36).
 *
 * The encoder pipeline observes [headroom] asynchronously and trims expensive
 * work (e.g. drops the encoder's bitrate, reduces frame rate, disables voice
 * DSP) when the system reports that we're approaching a thermal cliff. This
 * lets the recorder keep up with intense 120 Hz gameplay without dropping the
 * MP4 muxer pipeline mid-frame.
 */
class AdpfMonitor(private val context: Context) {

    data class Headroom(
        val cpu: Float,
        val gpu: Float,
    ) {
        /** A coarse 0.0..1.0 score where lower means we have less budget left. */
        fun health(): Float {
            // ADPF returns 0.0 when the device is fully thermally constrained
            // and 1.0 when it has all of its budget free.
            val cpuScore = cpu.coerceIn(0f, 1f)
            val gpuScore = gpu.coerceIn(0f, 1f)
            // Weight CPU & GPU equally for screen-record workloads.
            return (cpuScore * 0.5f) + (gpuScore * 0.5f)
        }
    }

    private val _headroom = MutableStateFlow(Headroom(cpu = 1f, gpu = 1f))
    val headroom: StateFlow<Headroom> = _headroom

    private var pollJob: Job? = null

    /**
     * Begins polling the ADPF APIs. We deliberately poll rather than register
     * a callback so we don't keep the powerd-bound thread busy when we're
     * pre-empted; on Android <16 this collapses into a no-op and we keep the
     * StateFlow at full headroom.
     */
    fun start(scope: CoroutineScope, intervalMs: Long = 750L) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                _headroom.value = pollOnce()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollOnce(): Headroom {
        if (Build.VERSION.SDK_INT < 36) return Headroom(cpu = 1f, gpu = 1f)
        return try {
            val pm = context.getSystemService(PowerManager::class.java)
                ?: return Headroom(1f, 1f)
            // Reflection guard: getCpuHeadroom / getGpuHeadroom are new APIs.
            // We compile-target API 36 but stay defensive against vendor builds
            // that ship the SDK with the methods stubbed out.
            val cpu = invokeFloat(pm, "getCpuHeadroom") ?: 1f
            val gpu = invokeFloat(pm, "getGpuHeadroom") ?: 1f
            Headroom(cpu, gpu)
        } catch (t: Throwable) {
            Log.w(TAG, "ADPF poll failed; assuming full headroom: ${t.message}")
            Headroom(1f, 1f)
        }
    }

    private fun invokeFloat(target: Any, name: String): Float? = try {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        (method?.invoke(target) as? Number)?.toFloat()
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val TAG = "AdpfMonitor"
    }
}
