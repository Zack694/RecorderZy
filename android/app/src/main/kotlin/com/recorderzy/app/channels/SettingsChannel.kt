package com.recorderzy.app.channels

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import com.recorderzy.app.recorder.ArrController
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Read-mostly platform helpers exposed to Flutter:
 *
 *   - `getDisplayMetrics`          : real width/height/density for sizing the recorder
 *   - `hasArrSupport`              : Android 16 ARR availability
 *   - `getSuggestedFrameRate`      : Android 16 ARR FPS hint
 *   - `isIgnoringBatteryOptimizations` / `requestIgnoreBatteryOptimizations`
 */
class SettingsChannel(
    private val activity: Activity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val channel = MethodChannel(messenger, CHANNEL)
    private val arr = ArrController(activity)

    fun attach() {
        channel.setMethodCallHandler(this)
    }

    fun detach() {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getDisplayMetrics" -> {
                val metrics = DisplayMetrics()
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.display
                } else {
                    @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
                }
                @Suppress("DEPRECATION") display?.getRealMetrics(metrics)
                result.success(
                    mapOf(
                        "widthPx" to metrics.widthPixels,
                        "heightPx" to metrics.heightPixels,
                        "densityDpi" to metrics.densityDpi,
                        "refreshRateHz" to (display?.refreshRate ?: 60f),
                    )
                )
            }
            "hasArrSupport" -> result.success(arr.hasArrSupport())
            "getSuggestedFrameRate" -> {
                val fallback = (call.argument<Int>("fallback") ?: 60).coerceIn(24, 240)
                result.success(arr.suggestedFrameRate(fallback))
            }
            "isIgnoringBatteryOptimizations" -> {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val enabled = pm?.isIgnoringBatteryOptimizations(activity.packageName) == true
                result.success(enabled)
            }
            "requestIgnoreBatteryOptimizations" -> {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
                runCatching { activity.startActivity(intent) }
                result.success(null)
            }
            "openBatterySettings" -> {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { activity.startActivity(intent) }
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    companion object {
        private const val CHANNEL = "recorderzy/settings"
    }
}
