package com.recorderzy.app.recorder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.recorderzy.app.MainApplication
import com.recorderzy.app.MainActivity
import com.recorderzy.app.R

/**
 * Foreground service that owns the active MediaProjection token, the
 * recorder engine and a persistent notification with Pause/Resume/Stop
 * quick actions.
 *
 * Android 14 introduced strict foreground-service-type enforcement; we
 * declare both `mediaProjection` and `microphone` types in the manifest so
 * we can capture system audio + the mic without falling foul of the new
 * privacy gate. The notification is non-dismissible while a session is
 * active to satisfy the same enforcement.
 */
class ScreenRecorderService : Service() {

    private var engine: ScreenRecorderEngine? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        try {
            when (action) {
                ACTION_START -> handleStart(intent!!)
                ACTION_PAUSE -> {
                    engine?.pause()
                    postNotification(paused = true)
                }
                ACTION_RESUME -> {
                    engine?.resume()
                    postNotification(paused = false)
                }
                ACTION_STOP -> handleStop()
                ACTION_SCREENSHOT -> handleScreenshot(intent!!)
                else -> {
                    Log.w(TAG, "Unknown action: $action")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling action '$action': ${e.message}", e)
            // Don't crash the whole app - gracefully stop.
            runCatching { stopForegroundCompat() }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(RecorderConfig.EXTRA_PROJECTION_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(RecorderConfig.EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(RecorderConfig.EXTRA_PROJECTION_DATA)
        }
        if (resultCode == -1 || data == null) {
            Log.w(TAG, "Refusing to start without projection token (resultCode=$resultCode, data=$data)")
            stopSelf()
            return
        }
        val cfg = RecorderConfig.fromIntent(intent)

        // CRITICAL: Enter foreground BEFORE calling getMediaProjection().
        // Android 14+ (API 34) will crash the app with
        // ForegroundServiceDidNotStartInTimeException if we call
        // getMediaProjection before the service is in foreground state.
        try {
            startForegroundSafely(buildNotification(paused = false, withScreenshot = false))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground: ${e.message}", e)
            stopSelf()
            return
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Log.e(TAG, "MediaProjectionManager is null")
            stopForegroundCompat()
            stopSelf()
            return
        }

        val projection: MediaProjection?
        try {
            projection = mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection threw: ${e.message}", e)
            stopForegroundCompat()
            stopSelf()
            return
        }

        if (projection == null) {
            Log.e(TAG, "MediaProjectionManager.getMediaProjection returned null")
            stopForegroundCompat()
            stopSelf()
            return
        }
        this.projection = projection
        projection.registerCallback(projectionCallback, null)

        try {
            engine = ScreenRecorderEngine(this, projection, cfg) { state ->
                when (state) {
                    ScreenRecorderEngine.State.RECORDING -> postNotification(paused = false)
                    ScreenRecorderEngine.State.PAUSED -> postNotification(paused = true)
                    ScreenRecorderEngine.State.STOPPED -> publishOutputAndStop()
                    ScreenRecorderEngine.State.ERROR -> publishOutputAndStop()
                    else -> Unit
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording engine: ${e.message}", e)
            runCatching { projection.stop() }
            this.projection = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun handleStop() {
        engine?.stop() ?: run {
            // Engine is null - just clean up.
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun handleScreenshot(intent: Intent) {
        val cfg = RecorderConfig.fromIntent(intent)
        val proj = projection ?: run {
            val resultCode = intent.getIntExtra(RecorderConfig.EXTRA_PROJECTION_RESULT_CODE, -1)
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(RecorderConfig.EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(RecorderConfig.EXTRA_PROJECTION_DATA)
            }
            if (resultCode == -1 || data == null) {
                Log.w(TAG, "Cannot screenshot without a projection token")
                return
            }
            try {
                startForegroundSafely(buildNotification(paused = false, withScreenshot = true))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enter foreground for screenshot: ${e.message}", e)
                return
            }
            getSystemService(MediaProjectionManager::class.java)
                ?.getMediaProjection(resultCode, data)
                ?.also { projection = it }
                ?: return
        }
        Screenshotter.capture(
            applicationContext,
            proj,
            cfg.widthPx,
            cfg.heightPx,
            cfg.densityDpi,
            scalePercent = intent.getIntExtra(EXTRA_SCREENSHOT_SCALE, 100).coerceIn(25, 100),
        ) { _ ->
            // If we don't have an active recording session, tear the projection down.
            if (engine == null) {
                runCatching { proj.stop() }
                projection = null
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun publishOutputAndStop() {
        val file = engine?.outputFile()
        engine = null
        runCatching {
            if (file != null && file.exists() && file.length() > 0) {
                val uri = MediaStoreWriter.newVideoUri(applicationContext, "RecorderZy-${System.currentTimeMillis()}.mp4")
                if (uri != null) {
                    MediaStoreWriter.copyFileTo(applicationContext, file, uri)
                    MediaStoreWriter.finalizeVideo(applicationContext, uri)
                }
                file.delete()
            }
        }
        stopForegroundCompat()
        stopSelf()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            engine?.stop()
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: must specify foreground service types
            startForeground(NOTIF_ID, notification, foregroundTypes())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, foregroundTypes())
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return t
    }

    private fun postNotification(paused: Boolean) {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIF_ID, buildNotification(paused, withScreenshot = false))
        }
    }

    private fun buildNotification(paused: Boolean, withScreenshot: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, MainApplication.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_record_dot)
            .setContentTitle(
                if (paused) getString(R.string.notif_recording_paused)
                else getString(R.string.notif_recording_title)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (paused) {
            builder.addAction(action(R.drawable.ic_play, R.string.action_resume, ACTION_RESUME))
        } else {
            builder.addAction(action(R.drawable.ic_pause, R.string.action_pause, ACTION_PAUSE))
        }
        builder.addAction(action(R.drawable.ic_stop, R.string.action_stop, ACTION_STOP))
        if (withScreenshot) {
            builder.addAction(action(R.drawable.ic_screenshot, R.string.action_screenshot, ACTION_SCREENSHOT))
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, actionStr: String): NotificationCompat.Action {
        val intent = Intent(this, ScreenRecorderService::class.java).setAction(actionStr)
        val pi = PendingIntent.getService(
            this, actionStr.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(icon, getString(label), pi)
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        runCatching { engine?.stop() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenRecorderService"
        private const val NOTIF_ID = 0xACE1

        const val ACTION_START = "com.recorderzy.app.START"
        const val ACTION_PAUSE = "com.recorderzy.app.PAUSE"
        const val ACTION_RESUME = "com.recorderzy.app.RESUME"
        const val ACTION_STOP = "com.recorderzy.app.STOP"
        const val ACTION_SCREENSHOT = "com.recorderzy.app.SCREENSHOT"

        const val EXTRA_SCREENSHOT_SCALE = "screenshotScalePercent"

        fun launchStart(context: Context, resultCode: Int, data: Intent, cfg: RecorderConfig) {
            val intent = Intent(context, ScreenRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(RecorderConfig.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(RecorderConfig.EXTRA_PROJECTION_DATA, data)
            cfg.applyExtras(intent)
            // START must use startForegroundService because the service
            // isn't in the foreground yet.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Send a command (pause/resume/stop) to an ALREADY-RUNNING service.
         * We use plain startService here — the service is already foreground
         * so we don't need startForegroundService (which would require
         * calling startForeground again within 5s on some OEMs).
         */
        fun launchAction(context: Context, action: String) {
            val intent = Intent(context, ScreenRecorderService::class.java).setAction(action)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Fallback: if the service isn't running yet, try foreground
                Log.w(TAG, "startService failed for $action, trying startForegroundService: ${e.message}")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "startForegroundService also failed for $action: ${e2.message}")
                }
            }
        }

        fun launchScreenshot(context: Context, resultCode: Int, data: Intent, cfg: RecorderConfig, scalePercent: Int = 100) {
            val intent = Intent(context, ScreenRecorderService::class.java)
                .setAction(ACTION_SCREENSHOT)
                .putExtra(RecorderConfig.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(RecorderConfig.EXTRA_PROJECTION_DATA, data)
                .putExtra(EXTRA_SCREENSHOT_SCALE, scalePercent)
            cfg.applyExtras(intent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
