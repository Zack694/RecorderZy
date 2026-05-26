package com.recorderzy.app.channels

import android.app.Activity
import android.content.Intent
import com.recorderzy.app.permissions.ProjectionRequestActivity
import com.recorderzy.app.recorder.RecorderConfig
import com.recorderzy.app.recorder.RecorderStateBus
import com.recorderzy.app.recorder.ScreenRecorderService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * MethodChannel surface used by the Flutter UI to drive the recorder.
 *
 * Method channel: `recorderzy/recorder`
 *  - `startRecording(Map config)` -> requests fresh MediaProjection then
 *    fires up the foreground service.
 *  - `pauseRecording()` / `resumeRecording()` / `stopRecording()`
 *  - `takeScreenshot(Map config, int scalePercent)` -> requests a fresh
 *    MediaProjection (the lifecycle rules require a one-shot token even
 *    for a single-frame capture) then dispatches into the service.
 *
 * Event channel: `recorderzy/recorder/state` - emits a Map every time the
 * RecorderStateBus phase or elapsed milliseconds change so the Flutter UI
 * can mirror the native timer pill.
 */
class RecorderChannel(
    private val activity: Activity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamJob: Job? = null
    private var sink: EventChannel.EventSink? = null

    fun attach() {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    fun detach() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        streamJob?.cancel()
        scope.cancel()
    }

    // -------------------------------------------------------------------- //
    // MethodCallHandler                                                    //
    // -------------------------------------------------------------------- //

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecording" -> handleStart(call, result)
            "pauseRecording" -> {
                ScreenRecorderService.launchAction(
                    activity, ScreenRecorderService.ACTION_PAUSE
                )
                result.success(null)
            }
            "resumeRecording" -> {
                ScreenRecorderService.launchAction(
                    activity, ScreenRecorderService.ACTION_RESUME
                )
                result.success(null)
            }
            "stopRecording" -> {
                ScreenRecorderService.launchAction(
                    activity, ScreenRecorderService.ACTION_STOP
                )
                result.success(null)
            }
            "takeScreenshot" -> handleScreenshot(call, result)
            "isRecording" -> result.success(
                RecorderStateBus.phase.value != RecorderStateBus.Phase.IDLE
            )
            else -> result.notImplemented()
        }
    }

    private fun handleStart(call: MethodCall, result: MethodChannel.Result) {
        val cfgMap = call.argument<Map<String, Any?>>("config")
            ?: return result.error("ARG", "Missing config", null)
        val cfg = RecorderConfig.fromMap(cfgMap)

        try {
            ProjectionRequestActivity.request(activity, object : ProjectionRequestActivity.ResultListener {
                override fun onProjectionGranted(resultCode: Int, data: Intent) {
                    try {
                        ScreenRecorderService.launchStart(activity, resultCode, data, cfg)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SERVICE", "Failed to start recorder: ${e.message}", null)
                    }
                }
                override fun onProjectionDenied() {
                    result.success(false)
                }
            })
        } catch (e: Exception) {
            result.error("PROJECTION", "Failed to request projection: ${e.message}", null)
        }
    }

    private fun handleScreenshot(call: MethodCall, result: MethodChannel.Result) {
        val cfgMap = call.argument<Map<String, Any?>>("config")
            ?: return result.error("ARG", "Missing config", null)
        val scale = (call.argument<Int>("scalePercent") ?: 100).coerceIn(25, 100)
        val cfg = RecorderConfig.fromMap(cfgMap)

        try {
            ProjectionRequestActivity.request(activity, object : ProjectionRequestActivity.ResultListener {
                override fun onProjectionGranted(resultCode: Int, data: Intent) {
                    try {
                        ScreenRecorderService.launchScreenshot(activity, resultCode, data, cfg, scale)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SERVICE", "Failed to take screenshot: ${e.message}", null)
                    }
                }
                override fun onProjectionDenied() {
                    result.success(false)
                }
            })
        } catch (e: Exception) {
            result.error("PROJECTION", "Failed to request projection: ${e.message}", null)
        }
    }

    // -------------------------------------------------------------------- //
    // StreamHandler                                                        //
    // -------------------------------------------------------------------- //

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        streamJob?.cancel()
        streamJob = scope.launch {
            RecorderStateBus.phase
                .combine(RecorderStateBus.elapsedMs) { p, ms -> p to ms }
                .collect { (phase, ms) ->
                    sink?.success(
                        mapOf(
                            "phase" to phase.name,
                            "elapsedMs" to ms,
                        )
                    )
                }
        }
    }

    override fun onCancel(arguments: Any?) {
        streamJob?.cancel()
        sink = null
    }

    companion object {
        private const val METHOD_CHANNEL = "recorderzy/recorder"
        private const val EVENT_CHANNEL = "recorderzy/recorder/state"
    }
}
