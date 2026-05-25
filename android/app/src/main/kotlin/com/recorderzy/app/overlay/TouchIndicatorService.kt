package com.recorderzy.app.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/**
 * Lightweight global touch-indicator overlay implemented as an
 * [AccessibilityService].
 *
 * Why AccessibilityService? It's the only root-free path to observe touches
 * happening outside our own app's windows. We opt into
 * `motionEventSources = SOURCE_TOUCHSCREEN` so the platform delivers raw
 * MotionEvents to [onMotionEvent]; we then materialise a small ring at the
 * touch coordinate inside a transparent system overlay window.
 *
 * The overlay window is added with [WindowManager.LayoutParams.FLAG_SECURE]
 * so the rings are visible to the user but completely absent from the
 * MediaProjection capture stream - the same mechanism used by the floating
 * control overlay to keep itself out of the saved video.
 */
class TouchIndicatorService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxRipples = 8

    override fun onServiceConnected() {
        super.onServiceConnected()
        configureService()
        attachOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: we only care about raw MotionEvents below.
    }

    override fun onInterrupt() {
        // Required override - no-op.
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (rootView == null) return
        // We render down/move events as small rings; up/cancel events fade
        // them out automatically via animation.
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_POINTER_DOWN ||
            action == MotionEvent.ACTION_MOVE
        ) {
            val pointerCount = event.pointerCount
            for (i in 0 until pointerCount) {
                spawnRing(event.getRawX(i), event.getRawY(i))
            }
        }
    }

    private fun configureService() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 50
        info.flags = (info.flags
            or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Available since API 31.
            info.motionEventSources = InputDevice.SOURCE_TOUCHSCREEN
        }
        serviceInfo = info
    }

    private fun attachOverlay() {
        if (rootView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val container = FrameLayout(this)
        rootView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(container, params) }
    }

    private fun spawnRing(rawX: Float, rawY: Float) {
        val container = rootView ?: return
        if (container.childCount >= maxRipples) {
            container.removeViewAt(0)
        }
        val sizePx = (resources.displayMetrics.density * 36).toInt()
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((resources.displayMetrics.density * 2).toInt(), Color.WHITE)
                setColor(Color.parseColor("#33FF7043"))
            }
            alpha = 0.85f
            scaleX = 0.6f
            scaleY = 0.6f
        }
        val lp = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            leftMargin = (rawX - sizePx / 2).toInt()
            topMargin = (rawY - sizePx / 2).toInt()
        }
        container.addView(ring, lp)
        ring.animate()
            .scaleX(1.4f).scaleY(1.4f).alpha(0f)
            .setDuration(360)
            .withEndAction { runCatching { container.removeView(ring) } }
            .start()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
