package com.recorderzy.app.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local state bus that the [ScreenRecorderService] publishes into and
 * the [com.recorderzy.app.overlay.FloatingOverlayService] / Flutter channel
 * subscribe to.
 *
 * Using a plain object instead of LocalBroadcastManager means we don't pay the
 * Intent serialisation cost for the high-frequency timer updates and we can
 * collect the StateFlows directly inside coroutines.
 */
object RecorderStateBus {

    enum class Phase { IDLE, RECORDING, PAUSED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    fun publishPhase(p: Phase) { _phase.value = p }
    fun publishElapsed(ms: Long) { _elapsedMs.value = ms }
    fun reset() {
        _phase.value = Phase.IDLE
        _elapsedMs.value = 0L
    }
}
