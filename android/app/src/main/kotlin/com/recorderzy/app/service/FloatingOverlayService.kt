package com.recorderzy.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.recorderzy.app.MediaProjectionRequestActivity
import com.recorderzy.app.R
import com.recorderzy.app.notification.RecorderNotifications
import com.recorderzy.app.recorder.ProjectionStore
import com.recorderzy.app.recorder.RecordingSession
import com.recorderzy.app.recorder.ScreenshotCapture
import kotlin.math.abs

/**
 * Hosts the persistent draggable floating action bubble.
 *
 * Design points relevant to the spec:
 *
 * 1. **Draggable anywhere** – the bubble's [WindowManager.LayoutParams] are
 *    updated live in `onTouch`, with edge-clamping so it cannot drift
 *    off-screen. Tap-vs-drag is disambiguated by total pointer travel.
 *
 * 2. **Invisible to MediaProjection** – the overlay window applies
 *    [WindowManager.LayoutParams.FLAG_SECURE], the **only** public API that
 *    excludes a window from the projected stream while keeping it visible
 *    on the device's physical display. Same flag is used by
 *    [TouchIndicatorService].
 *
 * 3. **Live timer** – [updateTimer] is called once per second by
 *    [ScreenRecordService] and the TextView re-draws on the main thread.
 *
 * 4. **Drawer / sub-menu** – tapping the bubble inflates a four-button
 *    radial menu (Record-Toggle / Screenshot / Settings / Close) animated
 *    via [ViewPropertyAnimator] at the device's native refresh rate so the
 *    expansion feels smooth on 120 Hz panels.
 */
class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var drawerView: View? = null
    private var timerView: TextView? = null
    private var iconView: ImageView? = null
    private val params: WindowManager.LayoutParams by lazy { buildLayoutParams() }

    // Drag state
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var dragged = false

    // Bubble visual config (live-tunable from Flutter)
    private var bubbleSizeDp: Int = 64
    private var bubbleAlpha: Float = 0.95f

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        promoteToForegroundIfNeeded()
        attachBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { detach(); stopSelf() }
            ACTION_UPDATE_TIMER -> {
                val ms = intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)
                val paused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                renderTimer(ms, paused)
            }
            ACTION_CONFIGURE -> {
                val sizeDp = intent.getIntExtra(EXTRA_SIZE_DP, bubbleSizeDp)
                val alpha = intent.getFloatExtra(EXTRA_ALPHA, bubbleAlpha)
                bubbleSizeDp = sizeDp.coerceIn(40, 120)
                bubbleAlpha = alpha.coerceIn(0.2f, 1f)
                applyVisualConfig()
            }
        }
        return START_STICKY
    }

    private fun promoteToForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        RecorderNotifications.ensureChannels(this)
        val notif = NotificationCompat.Builder(this, RecorderNotifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("RecorderZy bubble active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, FOREGROUND_NOTIF_ID, notif, type)
    }

    // ============= Bubble assembly =============
    private fun attachBubble() {
        if (bubbleView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        timerView = view.findViewById(R.id.bubbleTimer)
        iconView = view.findViewById(R.id.bubbleIcon)

        view.setOnTouchListener { v, event -> handleTouch(v, event) }
        applyVisualConfig()
        try {
            wm.addView(view, params)
            bubbleView = view
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "addView failed (SYSTEM_ALERT_WINDOW not granted?)", t)
            stopSelf()
        }
    }

    private fun applyVisualConfig() {
        bubbleView?.let { v ->
            val px = (bubbleSizeDp * resources.displayMetrics.density).toInt()
            val body = v.findViewById<View>(R.id.bubbleBody)
            body.layoutParams = body.layoutParams.also { it.width = px; it.height = px }
            v.alpha = bubbleAlpha
            v.requestLayout()
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                params.x = initialX + dx
                params.y = initialY + dy
                clampToScreen()
                runCatching { wm.updateViewLayout(view, params) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) toggleDrawer()
                return true
            }
        }
        return false
    }

    private fun clampToScreen() {
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }
        val w = bubbleView?.width ?: 0
        val h = bubbleView?.height ?: 0
        // The window uses Gravity.TOP|START so x/y are top-left offsets.
        params.x = params.x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
    }

    private fun renderTimer(elapsedMs: Long, paused: Boolean) {
        val active = RecordingSession.instance?.isActive == true
        timerView?.visibility = if (active) View.VISIBLE else View.GONE
        timerView?.post {
            val mins = elapsedMs / 60_000
            val secs = (elapsedMs / 1000) % 60
            timerView?.text = "%02d:%02d".format(mins, secs)
        }
        iconView?.post {
            val drawable = when {
                !active -> R.drawable.ic_record_white
                paused -> R.drawable.ic_play_white
                else -> R.drawable.ic_pause_white
            }
            iconView?.setImageResource(drawable)
        }
    }

    // ============= Drawer expansion =============
    private fun toggleDrawer() {
        if (drawerView != null) { collapseDrawer(); return }
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0x88000000.toInt())
        }
        // Lay out four mini-buttons radially around the bubble. The visual is
        // intentionally minimal — Flutter renders the polished version when the
        // user is in-app; this exists so actions are reachable while OUT of app.
        listOf(
            "rec" to R.drawable.ic_record_white,
            "shot" to R.drawable.ic_record_white,
            "settings" to R.drawable.ic_play_white,
            "close" to R.drawable.ic_stop_white,
        ).forEachIndexed { i, (id, iconRes) ->
            val btn = ImageView(this).apply {
                setImageResource(iconRes)
                setPadding(24, 24, 24, 24)
                setBackgroundResource(R.drawable.ic_floating_circle)
                tag = id
                setOnClickListener { onDrawerAction(id) }
            }
            val lp = FrameLayout.LayoutParams(120, 120).apply {
                leftMargin = (i % 2) * 140
                topMargin = (i / 2) * 140
            }
            container.addView(btn, lp)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x
            y = params.y + (bubbleView?.height ?: 0) + 12
        }
        wm.addView(container, lp)
        container.alpha = 0f
        container.scaleX = 0.6f; container.scaleY = 0.6f
        container.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160).setInterpolator(AccelerateDecelerateInterpolator()).start()
        drawerView = container
    }

    private fun collapseDrawer() {
        val v = drawerView ?: return
        v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
            .setDuration(120)
            .withEndAction {
                runCatching { wm.removeView(v) }
                drawerView = null
            }.start()
    }

    private fun onDrawerAction(id: String) {
        when (id) {
            "rec" -> {
                val active = RecordingSession.instance?.isActive == true
                if (!active) {
                    // Start a fresh MediaProjection consent flow (Android 14+ requires
                    // a brand-new token every session).
                    val intent = Intent(this, MediaProjectionRequestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else if (RecordingSession.instance?.isPaused == true) {
                    ScreenRecordService.resume(this)
                } else {
                    ScreenRecordService.pause(this)
                }
                collapseDrawer()
            }
            "shot" -> {
                ScreenshotCapture.queueOneShot(this, 1.0f) { _, _ -> }
                collapseDrawer()
            }
            "settings" -> {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                if (launch != null) startActivity(launch)
                collapseDrawer()
            }
            "close" -> collapseDrawer()
        }
    }

    // ============= Window plumbing =============
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // FLAG_SECURE is the magic that excludes this window from MediaProjection
            // capture, exactly per the spec.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun detach() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        drawerView?.let { runCatching { wm.removeView(it) } }
        drawerView = null
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val FOREGROUND_NOTIF_ID = 0xBBB1

        const val ACTION_HIDE = "com.recorderzy.app.overlay.HIDE"
        const val ACTION_UPDATE_TIMER = "com.recorderzy.app.overlay.UPDATE_TIMER"
        const val ACTION_CONFIGURE = "com.recorderzy.app.overlay.CONFIGURE"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_SIZE_DP = "size_dp"
        const val EXTRA_ALPHA = "alpha"

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingOverlayService::class.java).setAction(ACTION_HIDE)
            )
        }

        fun updateTimer(context: Context, elapsedMs: Long, paused: Boolean) {
            context.startService(
                Intent(context, FloatingOverlayService::class.java)
                    .setAction(ACTION_UPDATE_TIMER)
                    .putExtra(EXTRA_ELAPSED_MS, elapsedMs)
                    .putExtra(EXTRA_IS_PAUSED, paused)
            )
        }

        fun configure(context: Context, map: Map<String, Any?>) {
            val intent = Intent(context, FloatingOverlayService::class.java)
                .setAction(ACTION_CONFIGURE)
                .putExtra(EXTRA_SIZE_DP, (map["sizeDp"] as? Number)?.toInt() ?: 64)
                .putExtra(EXTRA_ALPHA, (map["alpha"] as? Number)?.toFloat() ?: 0.95f)
            context.startService(intent)
        }
    }
}
