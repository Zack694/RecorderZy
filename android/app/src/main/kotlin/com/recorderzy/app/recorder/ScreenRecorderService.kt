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
            startForegroundSafely(buildNotification(paused = paused, withScreenshot = withScreenshot), action)
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
        RecorderStateBus.publishError(null)
        // Token comes from ProjectionTokenHolder, NOT from intent extras.
        // See ProjectionTokenHolder for why.
        val (resultCode, data) = ProjectionTokenHolder.take() ?: run {
            Log.w(TAG, "handleStart: no projection token available")
            showErrorNotification("Recording failed", "No screen capture permission")
            stopForegroundCompat()
            stopSelf()
            return
        }

        // Re-read config from prefs (set right before the start).
        val cfg = RecorderLauncher.pendingConfig ?: RecorderConfig.defaults()

        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Log.e(TAG, "MediaProjectionManager is null")
            showErrorNotification("Recording failed", "System service unavailable")
            stopForegroundCompat()
            stopSelf()
            return
        }

        val projection: MediaProjection? = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Throwable) {
            Log.e(TAG, "getMediaProjection threw: ${e.message}", e)
            showErrorNotification("Recording failed", "Could not start screen capture")
            null
        }

        if (projection == null) {
            Log.e(TAG, "MediaProjectionManager.getMediaProjection returned null")
            showErrorNotification("Recording failed", "Could not start screen capture")
            stopForegroundCompat()
            stopSelf()
            return
        }
        this.projection = projection
        projection.registerCallback(projectionCallback, null)

        try {
            val eng = ScreenRecorderEngine(this, projection, cfg) { state ->
                when (state) {
                    ScreenRecorderEngine.State.RECORDING -> postNotification(paused = false)
                    ScreenRecorderEngine.State.PAUSED -> postNotification(paused = true)
                    ScreenRecorderEngine.State.STOPPED -> publishOutputAndStop()
                    ScreenRecorderEngine.State.ERROR -> {
                        showErrorNotification(
                            "Recording error",
                            engine?.lastError ?: "An error occurred during recording"
                        )
                        publishOutputAndStop()
                    }
                    else -> Unit
                }
            }
            engine = eng
            eng.start()
        } catch (e: Throwable) {
            // Surface the REAL reason so it can be read off the notification
            // instead of guessing. engine.lastError holds the last encoder
            // failure when all MediaRecorder attempts were exhausted.
            val reason = engine?.lastError ?: "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Failed to start recording engine: $reason", e)
            RecorderStateBus.publishError("Recording failed: $reason")
            showErrorNotification("Recording failed", reason)
            runCatching { projection.stop() }
            this.projection = null
            engine = null
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
                showErrorNotification("Screenshot failed", "No screen capture permission")
                stopForegroundCompat()
                stopSelf()
                return
            }
            val seeded = try {
                getSystemService(MediaProjectionManager::class.java)
                    ?.getMediaProjection(resultCode, data)
            } catch (e: Throwable) {
                Log.e(TAG, "getMediaProjection threw for screenshot: ${e.message}", e)
                null
            }
            if (seeded == null) {
                Log.e(TAG, "getMediaProjection returned null for screenshot")
                showErrorNotification("Screenshot failed", "Could not start screen capture")
                stopForegroundCompat()
                stopSelf()
                return
            }
            projection = seeded
            // ANDROID 14+ REQUIREMENT: a MediaProjection.Callback MUST be
            // registered before createVirtualDisplay() or it throws
            // IllegalStateException. The recording path already does this in
            // handleStart(); the standalone screenshot path previously did
            // not, which is why screenshots silently failed.
            seeded.registerCallback(projectionCallback, null)
            seeded
        }

        try {
            Screenshotter.capture(
                applicationContext,
                proj,
                cfg.widthPx,
                cfg.heightPx,
                cfg.densityDpi,
                scalePercent = scalePercent,
            ) { uri ->
                if (uri != null) {
                    Log.i(TAG, "Screenshot saved: $uri")
                    showSuccessNotification("Screenshot saved", "Saved to Pictures/RecorderZy")
                } else {
                    Log.e(TAG, "Screenshot capture returned no URI")
                    showErrorNotification("Screenshot failed", "Could not capture the screen")
                }
                // If we don't have an active recording session, tear the projection down.
                if (engine == null) {
                    runCatching { proj.stop() }
                    projection = null
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}", e)
            showErrorNotification("Screenshot failed", "Could not capture the screen")
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
        
        try {
            if (file != null && file.exists()) {
                if (file.length() > 0) {
                    Log.i(TAG, "Publishing video file: ${file.name} (${file.length()} bytes)")
                    val uri = MediaStoreWriter.newVideoUri(
                        applicationContext, 
                        "RecorderZy-${System.currentTimeMillis()}.mp4"
                    )
                    if (uri != null) {
                        MediaStoreWriter.copyFileTo(applicationContext, file, uri)
                        MediaStoreWriter.finalizeVideo(applicationContext, uri)
                        Log.i(TAG, "Video saved successfully to: $uri")
                        showSuccessNotification("Recording saved", "Tap to view")
                    } else {
                        Log.e(TAG, "Failed to create MediaStore URI")
                        showErrorNotification("Save failed", "Could not save to gallery")
                    }
                } else {
                    Log.w(TAG, "Output file is empty (0 bytes)")
                    showErrorNotification("Recording failed", "No video data captured")
                }
                file.delete()
            } else {
                Log.w(TAG, "No output file to publish")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish output: ${e.message}", e)
            showErrorNotification("Save failed", "Could not save recording")
        }
        
        runCatching { projection?.stop() }
        projection = null
        stopForegroundCompat()
        stopSelf()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            engine?.stop()
        }
    }

    private fun startForegroundSafely(notification: Notification, action: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, foregroundTypes(action))
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * Compute the foreground-service types to declare at runtime.
     *
     * CRITICAL ANDROID 14+ BUG FIX:
     *   On Android 14+ (API 34+) calling startForeground() with
     *   FOREGROUND_SERVICE_TYPE_MICROPHONE throws SecurityException when the
     *   RECORD_AUDIO runtime permission has NOT been granted. That exception
     *   is thrown before any recording work happens, so the service dies and
     *   "nothing happens" - no recording, no screenshot, no visible crash.
     *
     *   We therefore only add the MICROPHONE type when:
     *     1. This is a recording session (never for one-shot screenshots), AND
     *     2. RECORD_AUDIO is actually granted, AND
     *     3. The pending config actually intends to capture the mic.
     *
     *   MEDIA_PROJECTION is always safe to declare because the user just
     *   granted the projection consent token.
     */
    private fun foregroundTypes(action: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (action != ACTION_SCREENSHOT && hasRecordAudioPermission() && pendingAudioUsesMic()) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return t
    }

    private fun hasRecordAudioPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun pendingAudioUsesMic(): Boolean {
        val mode = RecorderLauncher.pendingConfig?.audioMode ?: return false
        return mode == RecorderConfig.AudioMode.MIC || mode == RecorderConfig.AudioMode.BOTH
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

    private fun showErrorNotification(title: String, message: String) {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            val openIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, MainApplication.CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_close)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .setCategory(Notification.CATEGORY_ERROR)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            mgr.notify(NOTIF_ID + 1, notification)
        }
    }

    private fun showSuccessNotification(title: String, message: String) {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            val openIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, MainApplication.CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_record_dot)
                .setContentTitle(title)
                .setContentText(message)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            mgr.notify(NOTIF_ID + 1, notification)
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
