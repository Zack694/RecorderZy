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
 * CRITICAL ANDROID 14+ INVARIANT:
 *   `startForegroundService()` requires the service to call `startForeground()`
 *   within ~5 seconds, otherwise the system crashes the *calling process* with
 *   ForegroundServiceDidNotStartInTimeException. This service therefore calls
 *   `startForegroundSafely()` as the very first operation in `onStartCommand`,
 *   *before* any branching, intent parsing, or possible exception.
 */
class ScreenRecorderService : Service() {

    private var engine: ScreenRecorderEngine? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        // ===========================================================
        // STEP 1: Enter foreground IMMEDIATELY, no matter what.
        // This satisfies the 5-second startForegroundService deadline
        // before we do any work that could throw or block.
        // ===========================================================
        try {
            val phase = RecorderStateBus.phase.value
            val paused = phase == RecorderStateBus.Phase.PAUSED
            val withScreenshot = action == ACTION_SCREENSHOT && phase == RecorderStateBus.Phase.IDLE
            startForegroundSafely(buildNotification(paused = paused, withScreenshot = withScreenshot))
        } catch (e: Throwable) {
            // If we can't even enter foreground, log and bail out. The system
            // will already be unhappy but we won't make it worse.
            Log.e(TAG, "startForeground failed in onStartCommand: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // ===========================================================
        // STEP 2: Now safely dispatch to the action handler.
        // ===========================================================
        try {
            when (action) {
                ACTION_START -> handleStart()
                ACTION_PAUSE -> {
                    engine?.pause()
                    postNotification(paused = true)
                }
                ACTION_RESUME -> {
                    engine?.resume()
                    postNotification(paused = false)
                }
                ACTION_STOP -> handleStop()
                ACTION_SCREENSHOT -> handleScreenshot()
                else -> {
                    Log.w(TAG, "Unknown action: $action")
                    if (engine == null) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling action '$action': ${e.message}", e)
            // We're already foreground so this is safe.
            runCatching { engine?.stop() }
            engine = null
            runCatching { projection?.stop() }
            projection = null
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        // Token comes from ProjectionTokenHolder, NOT from intent extras.
        // See ProjectionTokenHolder for why.
        val (resultCode, data) = ProjectionTokenHolder.take() ?: run {
            Log.w(TAG, "handleStart: no projection token available")
            stopForegroundCompat()
            stopSelf()
            return
        }

        // Re-read config from prefs (set right before the start).
        val cfg = RecorderLauncher.pendingConfig ?: RecorderConfig.defaults()

        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Log.e(TAG, "MediaProjectionManager is null")
            stopForegroundCompat()
            stopSelf()
            return
        }

        val projection: MediaProjection? = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Throwable) {
            Log.e(TAG, "getMediaProjection threw: ${e.message}", e)
            null
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
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start recording engine: ${e.message}", e)
            runCatching { projection.stop() }
            this.projection = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun handleStop() {
        val activeEngine = engine
        if (activeEngine == null) {
            stopForegroundCompat()
            stopSelf()
        } else {
            activeEngine.stop()
        }
    }

    private fun handleScreenshot() {
        val cfg = RecorderLauncher.pendingConfig ?: RecorderConfig.defaults()
        val scalePercent = RecorderLauncher.pendingScreenshotScale.coerceIn(25, 100)

        val proj = projection ?: run {
            // No active session - need to seed projection from the holder.
            val (resultCode, data) = ProjectionTokenHolder.take() ?: run {
                Log.w(TAG, "Cannot screenshot: no projection token")
                stopForegroundCompat()
                stopSelf()
                return
            }
            getSystemService(MediaProjectionManager::class.java)
                ?.getMediaProjection(resultCode, data)
                ?.also { projection = it } ?: run {
                Log.e(TAG, "getMediaProjection returned null for screenshot")
                stopForegroundCompat()
                stopSelf()
                return
            }
        }

        Screenshotter.capture(
            applicationContext,
            proj,
            cfg.widthPx,
            cfg.heightPx,
            cfg.densityDpi,
            scalePercent = scalePercent,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        } catch (e: Throwable) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        runCatching { engine?.stop() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        ProjectionTokenHolder.clear()
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

        /**
         * Start a fresh recording session. The caller must already have
         * deposited a projection token via [ProjectionTokenHolder.set] and
         * the desired config via [RecorderLauncher.pendingConfig].
         */
        fun launchStart(context: Context) {
            val intent = Intent(context, ScreenRecorderService::class.java)
                .setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "launchStart failed: ${e.message}", e)
            }
        }

        /**
         * Send a command to an already-running service. Uses plain
         * startService so we don't trip Android 14's 5-second
         * startForeground deadline a second time.
         */
        fun launchAction(context: Context, action: String) {
            val intent = Intent(context, ScreenRecorderService::class.java).setAction(action)
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                // Some OEMs throw IllegalStateException if the service was
                // killed; fall back to startForegroundService so the system
                // can revive it.
                Log.w(TAG, "startService failed for $action: ${e.message}")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                } catch (e2: Throwable) {
                    Log.e(TAG, "startForegroundService also failed for $action: ${e2.message}")
                }
            }
        }

        /**
         * Take a one-shot screenshot. Caller must deposit the projection
         * token via [ProjectionTokenHolder.set] and the config via
         * [RecorderLauncher.pendingConfig] / .pendingScreenshotScale.
         */
        fun launchScreenshot(context: Context) {
            val intent = Intent(context, ScreenRecorderService::class.java)
                .setAction(ACTION_SCREENSHOT)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "launchScreenshot failed: ${e.message}", e)
            }
        }
    }
}
