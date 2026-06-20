package com.recorderzy.app.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.recorderzy.app.MainActivity
import com.recorderzy.app.MainApplication
import com.recorderzy.app.R
import com.recorderzy.app.recorder.RecorderStateBus
import com.recorderzy.app.recorder.RecorderLauncher
import com.recorderzy.app.recorder.ScreenRecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Hosts the persistent floating control overlay.
 *
 * The overlay is rendered as a small circular handle that the user can drag
 * anywhere on screen. Tapping it expands an animated radial drawer with four
 * actions: record/pause toggle, screenshot, settings, and close. While the
 * recorder is running the timer (HH:MM:SS) is shown next to the handle.
 *
 * Critical implementation detail:
 *   - Both the handle window and the drawer window are added with
 *     [WindowManager.LayoutParams.FLAG_SECURE]. Surfaces marked secure are
 *     excluded from `MediaProjection` capture so they remain visible to the
 *     user but are completely absent from the saved video file.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var rootView: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var drawerExpanded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("recorderzy_overlay", Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_HIDE -> {
                tearDownOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STYLE -> {
                applyStyle(
                    sizeDp = intent.getIntExtra(EXTRA_SIZE_DP, prefs.getInt(KEY_SIZE_DP, 56)),
                    alpha = intent.getFloatExtra(EXTRA_ALPHA, prefs.getFloat(KEY_ALPHA, 0.92f)),
                )
                return START_STICKY
            }
        }
        ensureOverlayShown()
        return START_STICKY
    }

    private fun ensureOverlayShown() {
        if (rootView != null) return
        val sizeDp = prefs.getInt(KEY_SIZE_DP, 56)
        val styleAlpha = prefs.getFloat(KEY_ALPHA, 0.92f)

        val sizePx = dp(sizeDp)
        val drawerWidth = dp(220)
        val drawerHeight = dp(72)

        val container = FrameLayout(this)
        rootView = container
        // Force software rendering on the overlay. A hardware (GPU) secure
        // layer can't be read back by the screen compositor during capture, so
        // it gets composited as a BLACK box; a software-rendered secure layer
        // is more likely to be omitted from the capture instead.
        container.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        // -- Handle (the persistent circle) ----------------------------------
        val handle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1B1F2A"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }
        val recordDot = ImageView(this).apply {
            setImageResource(R.drawable.ic_record_dot)
            layoutParams = FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER
            )
        }
        handle.addView(recordDot)
        container.addView(handle)

        // -- Drawer (4 buttons, hidden by default) ----------------------------
        val drawer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(36).toFloat()
                setColor(Color.parseColor("#E61B1F2A"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(drawerWidth, drawerHeight).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = sizePx + dp(8)
            }
            scaleX = 0f
            scaleY = 0f
            alpha = 0f
        }

        val pauseBtn = drawerButton(R.drawable.ic_pause) { onPauseToggleClicked() }
        val stopBtn = drawerButton(R.drawable.ic_stop) { onStopClicked() }
        val screenshotBtn = drawerButton(R.drawable.ic_screenshot) { onScreenshotClicked() }
        val settingsBtn = drawerButton(R.drawable.ic_settings) { onSettingsClicked() }

        val items = listOf(pauseBtn, stopBtn, screenshotBtn, settingsBtn)
        val itemSize = dp(56)
        val gap = (drawerWidth - itemSize * items.size) / (items.size + 1)
        items.forEachIndexed { i, view ->
            val lp = FrameLayout.LayoutParams(itemSize, itemSize)
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            lp.leftMargin = gap + i * (itemSize + gap)
            drawer.addView(view, lp)
        }
        container.addView(drawer)

        // -- Timer pill (visible only while recording) ------------------------
        val timer = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#CC1B1F2A"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                topMargin = sizePx
                leftMargin = dp(4)
            }
            text = "00:00:00"
        }
        container.addView(timer)

        // -- Window ----------------------------------------------------------
        // DIAGNOSTIC: FLAG_SECURE temporarily removed. This tells us what is
        // actually drawing the black square in recordings:
        //   * If the button now appears as the NORMAL button (circle + icon)
        //     in the video, the black box was FLAG_SECURE being redacted to
        //     black by the ROM (secure-blackout) - so the fix path is
        //     accessibility-overlay / single-app / auto-hide.
        //   * If it's STILL a black square even without FLAG_SECURE, then the
        //     cause is the overlay rendering itself (a fixable bug), not the
        //     secure flag.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(16))
            y = prefs.getInt(KEY_Y, dp(120))
        }
        handleParams = params
        windowManager.addView(container, params)

        // The container's measured size depends on whether the drawer is
        // visible; force a layout pass so we know the bounds.
        applyStyle(sizeDp = sizeDp, alpha = styleAlpha)

        attachDragAndTap(handle, drawer, params)
        observeRecorderState(handle, recordDot, timer, pauseBtn)
    }

    private fun drawerButton(iconRes: Int, onClick: () -> Unit): View {
        val v = ImageView(this).apply {
            setImageResource(iconRes)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF2A3447"))
            }
            setOnClickListener { onClick() }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        return v
    }

    private fun attachDragAndTap(
        handle: View,
        drawer: View,
        params: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        val touchSlop = dp(6)

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        if (drawerExpanded) collapseDrawer(drawer)
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(rootView, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        toggleDrawer(drawer)
                    } else {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleDrawer(drawer: View) {
        if (drawerExpanded) collapseDrawer(drawer) else expandDrawer(drawer)
    }

    private fun expandDrawer(drawer: View) {
        drawerExpanded = true
        drawer.visibility = View.VISIBLE
        drawer.scaleX = 0.4f
        drawer.scaleY = 0.4f
        drawer.alpha = 0f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(drawer, "scaleX", 0.4f, 1f),
                ObjectAnimator.ofFloat(drawer, "scaleY", 0.4f, 1f),
                ObjectAnimator.ofFloat(drawer, "alpha", 0f, 1f),
            )
            // 220 ms + slight overshoot; on 120 Hz panels this completes in
            // ~26 frames so it never feels janky even during heavy recording.
            duration = 220
            interpolator = OvershootInterpolator(1.6f)
            start()
        }
    }

    private fun collapseDrawer(drawer: View) {
        drawerExpanded = false
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(drawer, "scaleX", 1f, 0.4f),
                ObjectAnimator.ofFloat(drawer, "scaleY", 1f, 0.4f),
                ObjectAnimator.ofFloat(drawer, "alpha", 1f, 0f),
            )
            duration = 160
            start()
        }
        mainHandler.postDelayed({ drawer.visibility = View.GONE }, 170)
    }

    private fun observeRecorderState(
        handle: View,
        dot: ImageView,
        timer: TextView,
        pauseBtn: View,
    ) {
        stateJob?.cancel()
        stateJob = scope.launch {
            RecorderStateBus.phase
                .combine(RecorderStateBus.elapsedMs) { phase, ms -> phase to ms }
                .collect { (phase, ms) ->
                    when (phase) {
                        RecorderStateBus.Phase.IDLE -> {
                            timer.visibility = View.GONE
                            (pauseBtn as? ImageView)?.setImageResource(R.drawable.ic_record_dot)
                            dot.alpha = 1f
                        }
                        RecorderStateBus.Phase.RECORDING -> {
                            timer.visibility = View.VISIBLE
                            timer.text = formatElapsed(ms)
                            (pauseBtn as? ImageView)?.setImageResource(R.drawable.ic_pause)
                            pulse(dot)
                        }
                        RecorderStateBus.Phase.PAUSED -> {
                            timer.visibility = View.VISIBLE
                            timer.text = "II  " + formatElapsed(ms)
                            (pauseBtn as? ImageView)?.setImageResource(R.drawable.ic_play)
                            dot.alpha = 0.45f
                        }
                    }
                }
        }
    }

    private fun pulse(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.animate().alpha(0.4f).setDuration(550).withEndAction {
            view.animate().alpha(1f).setDuration(550).withEndAction { pulse(view) }
                .start()
        }.start()
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return "%02d:%02d:%02d".format(hh, mm, ss)
    }

    private fun applyStyle(sizeDp: Int, alpha: Float) {
        prefs.edit().putInt(KEY_SIZE_DP, sizeDp).putFloat(KEY_ALPHA, alpha).apply()
        rootView?.alpha = alpha.coerceIn(0.2f, 1f)
        // We resize by walking the first child (the handle) and updating its
        // LayoutParams; doing this on a single view stays cheap on the main
        // thread.
        val first = rootView?.getChildAt(0) ?: return
        val newSize = dp(sizeDp)
        first.layoutParams = (first.layoutParams as FrameLayout.LayoutParams).apply {
            width = newSize
            height = newSize
        }
        first.requestLayout()
    }

    // -- Action handlers -----------------------------------------------------

    private fun onPauseToggleClicked() {
        when (RecorderStateBus.phase.value) {
            RecorderStateBus.Phase.IDLE -> {
                // Trigger projection request directly so the user doesn't
                // have to bounce through MainActivity.
                val cfg = RecorderLauncher.autoConfig(this)
                runCatching { RecorderLauncher.startRecording(this, cfg) }
            }
            RecorderStateBus.Phase.RECORDING -> {
                ScreenRecorderService.launchAction(this, ScreenRecorderService.ACTION_PAUSE)
            }
            RecorderStateBus.Phase.PAUSED -> {
                ScreenRecorderService.launchAction(this, ScreenRecorderService.ACTION_RESUME)
            }
        }
    }

    private fun onScreenshotClicked() {
        // Use auto-config so the screenshot just works at native resolution,
        // even when the user hasn't customised settings yet. Uses the same
        // MediaProjection capture path as the main app.
        val cfg = RecorderLauncher.autoConfig(this)
        runCatching { RecorderLauncher.takeScreenshot(this, cfg, scalePercent = 100) }
    }

    private fun onSettingsClicked() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_OPEN_SETTINGS, true)
        startActivity(intent)
    }

    private fun onStopClicked() {
        // Stop an active (or paused) recording from the floating drawer.
        if (RecorderStateBus.phase.value != RecorderStateBus.Phase.IDLE) {
            ScreenRecorderService.launchAction(this, ScreenRecorderService.ACTION_STOP)
        }
        val drawer = rootView?.getChildAt(1) ?: return
        if (drawerExpanded) collapseDrawer(drawer)
    }

    private fun tearDownOverlay() {
        stateJob?.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        handleParams = null
        drawerExpanded = false
    }

    private fun startForegroundCompat() {
        val openIntent = Intent().setComponent(ComponentName(this, MainActivity::class.java))
        val pi = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, MainApplication.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_record_dot)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Pre-API 34 there is no SPECIAL_USE foreground type. Pass 0 so
            // the system uses whichever foregroundServiceType is declared on
            // the <service> element in the manifest (or none on Q/R/S/T).
            startForeground(NOTIF_ID, notif, 0)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        tearDownOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIF_ID = 0xACE2

        const val ACTION_SHOW = "com.recorderzy.app.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.recorderzy.app.OVERLAY_HIDE"
        const val ACTION_UPDATE_STYLE = "com.recorderzy.app.OVERLAY_STYLE"

        const val EXTRA_SIZE_DP = "sizeDp"
        const val EXTRA_ALPHA = "alpha"

        const val EXTRA_REQUEST_RECORD = "requestRecord"
        const val EXTRA_REQUEST_SCREENSHOT = "requestScreenshot"
        const val EXTRA_OPEN_SETTINGS = "openSettings"

        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_SIZE_DP = "sizeDp"
        private const val KEY_ALPHA = "alpha"

        fun show(context: Context) = launchAction(context, ACTION_SHOW)
        fun hide(context: Context) = launchAction(context, ACTION_HIDE)

        fun updateStyle(context: Context, sizeDp: Int, alpha: Float) {
            val intent = Intent(context, FloatingOverlayService::class.java)
                .setAction(ACTION_UPDATE_STYLE)
                .putExtra(EXTRA_SIZE_DP, sizeDp)
                .putExtra(EXTRA_ALPHA, alpha)
            startCompat(context, intent)
        }

        private fun launchAction(context: Context, action: String) {
            val intent = Intent(context, FloatingOverlayService::class.java).setAction(action)
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w("FloatingOverlay", "startCompat failed: ${t.message}")
            }
        }
    }
}
