package com.recorderzy.app.channels

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.recorderzy.app.overlay.FloatingOverlayService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Bridges the Flutter UI to the native floating overlay.
 *
 *   - `requestPermission` / `hasPermission` for SYSTEM_ALERT_WINDOW.
 *   - `showOverlay` / `hideOverlay` toggles the overlay service lifecycle.
 *   - `setStyle(sizeDp, alpha)` fires a hot-update so the overlay's circle
 *     resizes / changes opacity without restarting.
 */
class OverlayChannel(
    private val activity: Activity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val channel = MethodChannel(messenger, CHANNEL)

    fun attach() {
        channel.setMethodCallHandler(this)
    }

    fun detach() {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "hasPermission" -> result.success(Settings.canDrawOverlays(activity))
            "requestPermission" -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
                result.success(null)
            }
            "showOverlay" -> {
                FloatingOverlayService.show(activity)
                result.success(null)
            }
            "hideOverlay" -> {
                FloatingOverlayService.hide(activity)
                result.success(null)
            }
            "setStyle" -> {
                val sizeDp = (call.argument<Int>("sizeDp") ?: 56).coerceIn(40, 96)
                val alpha = (call.argument<Double>("alpha") ?: 0.92).toFloat().coerceIn(0.2f, 1f)
                FloatingOverlayService.updateStyle(activity, sizeDp, alpha)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    companion object {
        private const val CHANNEL = "recorderzy/overlay"
    }
}
