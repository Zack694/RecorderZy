package com.recorderzy.app.recorder

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.recorderzy.app.permissions.ProjectionRequestActivity

/**
 * Single source of truth for "user wants to record / take a screenshot".
 *
 * Both the Flutter MethodChannel layer and the Floating Overlay button
 * handlers funnel through here so the projection-consent + token-handoff
 * sequence stays consistent and avoids the Intent-in-Intent parcel
 * weirdness that crashes Android 14+ services on Xiaomi/Poco.
 */
object RecorderLauncher {

    @Volatile var pendingConfig: RecorderConfig? = null
    @Volatile var pendingScreenshotScale: Int = 100

    fun startRecording(
        context: Context,
        cfg: RecorderConfig,
        onResult: (Boolean) -> Unit = {},
    ) {
        pendingConfig = cfg
        ProjectionRequestActivity.request(context, object : ProjectionRequestActivity.ResultListener {
            override fun onProjectionGranted(resultCode: Int, data: Intent) {
                ProjectionTokenHolder.set(resultCode, data)
                ScreenRecorderService.launchStart(context)
                onResult(true)
            }
            override fun onProjectionDenied() {
                pendingConfig = null
                onResult(false)
            }
        })
    }

    fun takeScreenshot(
        context: Context,
        cfg: RecorderConfig,
        scalePercent: Int,
        onResult: (Boolean) -> Unit = {},
    ) {
        pendingConfig = cfg
        pendingScreenshotScale = scalePercent.coerceIn(25, 100)
        ProjectionRequestActivity.request(context, object : ProjectionRequestActivity.ResultListener {
            override fun onProjectionGranted(resultCode: Int, data: Intent) {
                ProjectionTokenHolder.set(resultCode, data)
                ScreenRecorderService.launchScreenshot(context)
                onResult(true)
            }
            override fun onProjectionDenied() {
                onResult(false)
            }
        })
    }

    /**
     * Auto-fill a RecorderConfig with current display metrics. Used as a
     * fallback when the floating overlay needs a config but doesn't have
     * the user's full SharedPreferences snapshot at hand.
     */
    fun autoConfig(context: Context): RecorderConfig {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: 420
        return RecorderConfig(
            widthPx = w,
            heightPx = h,
            densityDpi = dpi,
            frameRate = 60,
            bitrateBps = 12_000_000,
            useApv = false,
            audioMode = RecorderConfig.AudioMode.MIC,
            noiseSuppression = false,
            voicePreset = RecorderConfig.VoicePreset.NORMAL,
            showTouches = false,
            outputFileNameHint = "RecorderZy",
        )
    }
}
