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

    enum class Action { START, PAUSE, RESUME, STOP, SCREENSHOT }

    private var engine: ScreenRecorderEngine? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
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
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(RecorderConfig.EXTRA_PROJECTION_RESULT_CODE, -1)
        val data: Intent? = intent.getParcelableExtra(RecorderConfig.EXTRA_PROJECTION_DATA)
        if (resultCode == -1 || data == null) {
            Log.w(TAG, "Refusing to start without projection token")
            stopSelf()
            return
        }
        val cfg = RecorderConfig.fromIntent(intent)

        // Enter the foreground BEFORE asking MediaProjectionManager for a
        // projection - Android 14+ enforces this ordering for
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
        startForegroundSafely(buildNotification(paused = false, withScreenshot = false))

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = mgr?.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "MediaProjectionManager.getMediaProjection returned null")
            stopForegroundCompat()
            stopSelf()
            return
        }
        this.projection = projection
        projection.registerCallback(projectionCallback, null)

        engine = ScreenRecorderEngine(this, projection, cfg) { state ->
            when (state) {
                ScreenRecorderEngine.State.RECORDING -> postNotification(paused = false)
                ScreenRecorderEngine.State.PAUSED -> postNotification(paused = true)
                ScreenRecorderEngine.State.STOPPED -> {
                    publishOutputAndStop()
                }
                ScreenRecorderEngine.State.ERROR -> {
                    publishOutputAndStop()
                }
                else -> Unit
            }
        }.also { it.start() }
    }

    private fun handleStop() {
        engine?.stop()
    }

    private fun handleScreenshot(intent: Intent) {
        val cfg = RecorderConfig.fromIntent(intent)
        val proj = projection ?: run {
            // Caller asked for a one-shot screenshot but we don't have an
            // active projection; ProjectionRequestActivity must seed one.
            val resultCode = intent.getIntExtra(RecorderConfig.EXTRA_PROJECTION_RESULT_CODE, -1)
            val data: Intent? = intent.getParcelableExtra(RecorderConfig.EXTRA_PROJECTION_DATA)
            if (resultCode == -1 || data == null) {
                Log.w(TAG, "Cannot screenshot without a projection token")
                return
            }
            startForegroundSafely(buildNotification(paused = false, withScreenshot = true))
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
            // System revoked our projection (e.g. user tapped the system
            // "Stop sharing" chip). Make sure we tear the engine down too.
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
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIF_ID, buildNotification(paused, withScreenshot = false))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun launchAction(context: Context, action: String) {
            val intent = Intent(context, ScreenRecorderService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
