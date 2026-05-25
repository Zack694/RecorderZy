package com.recorderzy.app.channels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.recorderzy.app.MainActivity
import com.recorderzy.app.perf.AdpfMonitor
import com.recorderzy.app.perf.ArrManager
import com.recorderzy.app.recorder.RecordingSession
import com.recorderzy.app.recorder.ScreenshotCapture
import com.recorderzy.app.service.FloatingOverlayService
import com.recorderzy.app.service.ScreenRecordService
import com.recorderzy.app.service.TouchIndicatorService
import com.recorderzy.app.storage.MediaStoreHelper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Single-source-of-truth router between Flutter (Dart) and the native Android
 * recording stack. Every method exposed here is invoked from
 * `lib/services/recorder_channel.dart`.
 *
 * Channels are split by responsibility so a slow JSON serialise on one side
 * (e.g. screenshot bytes) cannot block the other (e.g. lightweight permission
 * polling).
 */
object MethodChannels {

    private const val CHANNEL_RECORDER = "recorderzy/recorder"
    private const val CHANNEL_OVERLAY = "recorderzy/overlay"
    private const val CHANNEL_PERMISSIONS = "recorderzy/permissions"
    private const val CHANNEL_PERFORMANCE = "recorderzy/performance"

    private var recorderChannel: MethodChannel? = null
    private var overlayChannel: MethodChannel? = null
    private var permissionsChannel: MethodChannel? = null
    private var performanceChannel: MethodChannel? = null

    private var pendingProjectionResult: MethodChannel.Result? = null

    fun register(
        context: Context,
        messenger: BinaryMessenger,
        requestProjection: () -> Unit,
    ) {
        recorderChannel = MethodChannel(messenger, CHANNEL_RECORDER).apply {
            setMethodCallHandler { call, result -> handleRecorder(context, call, result, requestProjection) }
        }
        overlayChannel = MethodChannel(messenger, CHANNEL_OVERLAY).apply {
            setMethodCallHandler { call, result -> handleOverlay(context, call, result) }
        }
        permissionsChannel = MethodChannel(messenger, CHANNEL_PERMISSIONS).apply {
            setMethodCallHandler { call, result -> handlePermissions(context, call, result) }
        }
        performanceChannel = MethodChannel(messenger, CHANNEL_PERFORMANCE).apply {
            setMethodCallHandler { call, result -> handlePerformance(context, call, result) }
        }
    }

    fun unregister() {
        recorderChannel?.setMethodCallHandler(null)
        overlayChannel?.setMethodCallHandler(null)
        permissionsChannel?.setMethodCallHandler(null)
        performanceChannel?.setMethodCallHandler(null)
        recorderChannel = null
        overlayChannel = null
        permissionsChannel = null
        performanceChannel = null
    }

    // ====================================================================
    // recorderzy/recorder
    // ====================================================================
    private fun handleRecorder(
        context: Context,
        call: MethodCall,
        result: MethodChannel.Result,
        requestProjection: () -> Unit,
    ) {
        when (call.method) {
            "startRecording" -> {
                pendingProjectionResult = result
                // Persist the desired settings so the service that picks up the
                // projection token has access to bitrate, fps, codec, audio mode etc.
                RecordingSession.pendingSettings = RecordingSession.Settings.fromMap(call.arguments as? Map<*, *>)
                requestProjection()
                // result is resolved later in [onProjectionResult]
            }
            "pauseRecording" -> {
                ScreenRecordService.pause(context)
                result.success(true)
            }
            "resumeRecording" -> {
                ScreenRecordService.resume(context)
                result.success(true)
            }
            "stopRecording" -> {
                ScreenRecordService.stop(context)
                result.success(true)
            }
            "isRecording" -> result.success(MainActivity.isRecording())
            "captureScreenshot" -> {
                val scale = (call.argument<Number>("scale") ?: 1.0).toFloat()
                ScreenshotCapture.queueOneShot(context, scale) { uri, error ->
                    if (error != null) result.error("SCREENSHOT_FAILED", error.message, null)
                    else result.success(uri?.toString())
                }
            }
            "listRecordings" -> {
                result.success(MediaStoreHelper.listAlbumItems(context))
            }
            "updateLiveSettings" -> {
                @Suppress("UNCHECKED_CAST")
                val map = call.arguments as? Map<String, Any?> ?: emptyMap()
                ScreenRecordService.applyLiveSettings(context, map)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    // ====================================================================
    // recorderzy/overlay
    // ====================================================================
    private fun handleOverlay(context: Context, call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "showFloatingBubble" -> {
                FloatingOverlayService.start(context)
                result.success(true)
            }
            "hideFloatingBubble" -> {
                FloatingOverlayService.stop(context)
                result.success(true)
            }
            "configureBubble" -> {
                @Suppress("UNCHECKED_CAST")
                val map = call.arguments as? Map<String, Any?> ?: emptyMap()
                FloatingOverlayService.configure(context, map)
                result.success(true)
            }
            "showTouchIndicator" -> {
                TouchIndicatorService.start(context)
                result.success(true)
            }
            "hideTouchIndicator" -> {
                TouchIndicatorService.stop(context)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    // ====================================================================
    // recorderzy/permissions
    // ====================================================================
    private fun handlePermissions(context: Context, call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "canDrawOverlays" -> result.success(Settings.canDrawOverlays(context))
            "openOverlaySettings" -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.success(true)
            }
            "isIgnoringBatteryOptimizations" -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                result.success(pm.isIgnoringBatteryOptimizations(context.packageName))
            }
            "requestIgnoreBatteryOptimizations" -> {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.success(true)
            }
            "openAppNotificationSettings" -> {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    // ====================================================================
    // recorderzy/performance
    // ====================================================================
    private fun handlePerformance(context: Context, call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getThermalHeadroom" -> {
                result.success(AdpfMonitor.thermalHeadroom(context))
            }
            "getCpuHeadroom" -> result.success(AdpfMonitor.cpuHeadroom(context))
            "getGpuHeadroom" -> result.success(AdpfMonitor.gpuHeadroom(context))
            "hasArrSupport" -> result.success(ArrManager.hasArrSupport(context))
            "getSuggestedFrameRate" -> {
                val category = call.argument<Int>("category") ?: 0
                result.success(ArrManager.suggestedFrameRate(context, category))
            }
            "deviceInfo" -> {
                result.success(
                    mapOf(
                        "sdk" to Build.VERSION.SDK_INT,
                        "release" to Build.VERSION.RELEASE,
                        "model" to Build.MODEL,
                        "manufacturer" to Build.MANUFACTURER
                    )
                )
            }
            else -> result.notImplemented()
        }
    }

    // ====================================================================
    // Bridge between MainActivity's ActivityResultLauncher and the call result
    // that started the projection consent flow.
    // ====================================================================
    fun onProjectionResult(context: Context, resultCode: Int, data: Intent?) {
        val pending = pendingProjectionResult
        pendingProjectionResult = null
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            ScreenRecordService.startWithProjection(context, resultCode, data)
            pending?.success(true)
        } else {
            pending?.error("PROJECTION_DENIED", "User denied screen capture consent", null)
        }
    }

    /**
     * Push a status update from the native layer (recording started/paused/stopped,
     * timer ticks, thermal alerts) up to Flutter.
     */
    fun pushRecorderEvent(method: String, args: Any?) {
        recorderChannel?.invokeMethod(method, args)
    }

    fun pushOverlayEvent(method: String, args: Any?) {
        overlayChannel?.invokeMethod(method, args)
    }
}
