package com.recorderzy.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.recorderzy.app.R
import com.recorderzy.app.notification.RecorderNotifications

/**
 * Lightweight overlay that renders fading dots at user touch coordinates.
 *
 * The window is published with [WindowManager.LayoutParams.FLAG_SECURE] so the
 * dots are visible to the user but completely excluded from the MediaProjection
 * capture — guaranteeing the spec's "indicators visible on screen but absent
 * from the saved video" property.
 *
 * Touch coordinates are fed in via [reportTouch] from a companion
 * [com.recorderzy.app.service.TouchAccessibilityService] (registered separately
 * by the user) — without an Accessibility permission the OS does not provide
 * any way to observe system-wide touches.
 */
class TouchIndicatorService : Service() {

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeDots = mutableListOf<View>()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        promoteToForeground()
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        RecorderNotifications.ensureChannels(this)
        val notif = NotificationCompat.Builder(this, RecorderNotifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Touch indicator active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, FOREGROUND_NOTIF_ID, notif, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPORT -> {
                val x = intent.getFloatExtra(EXTRA_X, -1f)
                val y = intent.getFloatExtra(EXTRA_Y, -1f)
                if (x >= 0 && y >= 0) renderDot(x, y)
            }
            ACTION_HIDE -> { clearAll(); stopSelf() }
        }
        return START_STICKY
    }

    fun reportTouch(x: Float, y: Float) {
        startService(
            Intent(this, TouchIndicatorService::class.java)
                .setAction(ACTION_REPORT)
                .putExtra(EXTRA_X, x)
                .putExtra(EXTRA_Y, y)
        )
    }

    private fun renderDot(x: Float, y: Float) {
        val dot = View(this).apply { setBackgroundResource(R.drawable.touch_dot) }
        val size = (40 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - size / 2).toInt()
            this.y = (y - size / 2).toInt()
        }
        try {
            wm.addView(dot, lp)
            activeDots += dot
            dot.alpha = 0.9f
            dot.scaleX = 0.5f; dot.scaleY = 0.5f
            dot.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(420)
                .withEndAction {
                    runCatching { wm.removeView(dot) }
                    activeDots -= dot
                }.start()
        } catch (_: Throwable) { /* SYSTEM_ALERT_WINDOW may not be granted */ }
    }

    private fun clearAll() {
        for (d in activeDots) runCatching { wm.removeView(d) }
        activeDots.clear()
    }

    override fun onDestroy() {
        clearAll()
        instance = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val FOREGROUND_NOTIF_ID = 0xBBB2

        const val ACTION_REPORT = "com.recorderzy.app.touch.REPORT"
        const val ACTION_HIDE = "com.recorderzy.app.touch.HIDE"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"

        @Volatile var instance: TouchIndicatorService? = null

        fun start(context: Context) {
            val intent = Intent(context, TouchIndicatorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TouchIndicatorService::class.java).setAction(ACTION_HIDE)
            )
        }
    }
}
