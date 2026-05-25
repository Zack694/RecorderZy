package com.recorderzy.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.recorderzy.app.channels.MethodChannels
import com.recorderzy.app.notification.RecorderNotifications
import com.recorderzy.app.recorder.ProjectionStore
import com.recorderzy.app.recorder.RecordingSession
import com.recorderzy.app.recorder.ScreenRecorder
import com.recorderzy.app.storage.MediaStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single foreground [Service] hosting an in-flight recording. Owns:
 *
 *  - The [MediaProjection] token (released exactly when the user stops).
 *  - The [ScreenRecorder] encoder pipeline.
 *  - The persistent notification with Pause / Resume / Stop quick actions.
 *  - The 1-second timer tick that pushes elapsed time to the notification AND
 *    to the floating bubble & Flutter UI.
 *
 * Stopped via either [ACTION_STOP] (notification button), the Flutter MethodChannel,
 * or the floating bubble's drawer.
 */
class ScreenRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var outputUri: android.net.Uri? = null

    override fun onCreate() {
        super.onCreate()
        RecorderNotifications.ensureChannels(this)
        // Post the foreground notification immediately so we comply with the 5s rule
        // even before the projection token is consumed.
        startInForeground(isPaused = false, elapsedMs = 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> handleStart(intent)
            ACTION_PAUSE -> doPause()
            ACTION_RESUME -> doResume()
            ACTION_STOP -> doStop()
            ACTION_APPLY_LIVE_SETTINGS -> {
                // Future hook for live bitrate adjustment — recorder restart not required.
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (recorder != null) return  // already running

        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
        val projectionData: Intent = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            ?: run {
                stopSelf(); return
            }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, projectionData)
            ?: run {
                Log.w(TAG, "MediaProjection token denied / null");  stopSelf(); return
            }

        // Even before encoding starts, share the projection with the screenshot path.
        ProjectionStore.current = mp
        projection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection.onStop — projection token revoked")
                doStop()
            }
        }, null)

        val baseSettings = RecordingSession.pendingSettings
        val sized = baseSettings.applyDeviceSize(this)

        val displayName = "RecorderZy_${System.currentTimeMillis()}.mp4"
        val (uri, pfd) = MediaStoreHelper.openVideoForWrite(this, displayName)
        outputUri = uri

        val session = RecordingSession.create(sized).apply {
            isActive = true
            startedAtNanos = System.nanoTime()
            outputUri = uri
        }

        recorder = ScreenRecorder(
            context = this,
            projection = mp,
            outputPfd = pfd,
            outputFile = null,
            settings = sized,
            onError = { t -> Log.e(TAG, "recorder error", t); doStop() }
        ).also {
            session.recorder = it
            it.start()
        }

        // Re-promote to foreground with the proper service types. We must do this
        // *after* the projection token is consumed so Android 14+ accepts the
        // mediaProjection foregroundServiceType.
        startInForeground(isPaused = false, elapsedMs = 0L)
        startTimerTicks()
        MethodChannels.pushRecorderEvent("onRecordingStarted", mapOf("uri" to uri.toString()))
    }

    private fun startInForeground(isPaused: Boolean, elapsedMs: Long) {
        val notif = RecorderNotifications.buildRecordingNotification(this, isPaused, elapsedMs)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(
            this,
            RecorderNotifications.NOTIF_ID_RECORDING,
            notif,
            type
        )
    }

    private fun startTimerTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1000L)
                val session = RecordingSession.instance ?: break
                if (!session.isActive) break
                val elapsed = session.elapsedMs()
                NotificationManagerCompat.from(this@ScreenRecordService).notify(
                    RecorderNotifications.NOTIF_ID_RECORDING,
                    RecorderNotifications.buildRecordingNotification(
                        this@ScreenRecordService,
                        isPaused = session.isPaused,
                        elapsedMs = elapsed
                    )
                )
                MethodChannels.pushRecorderEvent("onTick", mapOf("elapsedMs" to elapsed, "paused" to session.isPaused))
                FloatingOverlayService.updateTimer(this@ScreenRecordService, elapsed, session.isPaused)
            }
        }
    }

    private fun doPause() {
        val session = RecordingSession.instance ?: return
        if (session.isPaused) return
        recorder?.pause()
        session.isPaused = true
        startInForeground(isPaused = true, elapsedMs = session.elapsedMs())
        MethodChannels.pushRecorderEvent("onPaused", null)
    }

    private fun doResume() {
        val session = RecordingSession.instance ?: return
        if (!session.isPaused) return
        recorder?.resume()
        session.isPaused = false
        startInForeground(isPaused = false, elapsedMs = session.elapsedMs())
        MethodChannels.pushRecorderEvent("onResumed", null)
    }

    private fun doStop() {
        val session = RecordingSession.instance
        try {
            recorder?.stop()
        } catch (t: Throwable) { Log.w(TAG, "stop error", t) }
        recorder = null
        session?.isActive = false

        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        ProjectionStore.current = null

        outputUri?.let { MediaStoreHelper.finaliseVideo(this, it) }

        MethodChannels.pushRecorderEvent("onRecordingStopped", mapOf("uri" to outputUri?.toString()))
        outputUri = null

        RecordingSession.clear()
        tickJob?.cancel()
        scope.cancel()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tickJob?.cancel()
    }

    companion object {
        private const val TAG = "ScreenRecordService"

        const val ACTION_START_PROJECTION = "com.recorderzy.app.action.START_PROJECTION"
        const val ACTION_PAUSE = "com.recorderzy.app.action.PAUSE"
        const val ACTION_RESUME = "com.recorderzy.app.action.RESUME"
        const val ACTION_STOP = "com.recorderzy.app.action.STOP"
        const val ACTION_APPLY_LIVE_SETTINGS = "com.recorderzy.app.action.APPLY_LIVE_SETTINGS"

        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java)
                .setAction(ACTION_START_PROJECTION)
                .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }

        fun applyLiveSettings(context: Context, map: Map<String, Any?>) {
            // Accept live-tunable knobs (e.g. show-touches toggle); restart-required
            // settings are ignored mid-recording for stability.
            map["showTouches"]?.let { show ->
                if (show as? Boolean == true) TouchIndicatorService.start(context)
                else TouchIndicatorService.stop(context)
            }
        }

        private fun RecordingSession.Settings.applyDeviceSize(ctx: Context): RecordingSession.Settings {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics().also {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(it)
            }
            return copy(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
            )
        }
    }
}
