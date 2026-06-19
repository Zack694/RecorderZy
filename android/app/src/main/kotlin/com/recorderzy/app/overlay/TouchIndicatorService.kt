package com.recorderzy.app.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.recorderzy.app.recorder.MediaStoreWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

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
        instance = this
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
        instance = null
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TouchIndicator"

        /**
         * Live reference to the connected accessibility service, used to drive
         * real (non-MediaProjection) screenshots. Null when the user hasn't
         * enabled "RecorderZy" under Accessibility settings.
         */
        @Volatile
        var instance: TouchIndicatorService? = null
            private set

        /** Whether a real screenshot can be taken right now. */
        fun canCaptureScreenshot(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null
    }

    /**
     * Takes a real screenshot of the default display via the Accessibility
     * service `takeScreenshot()` API (no screen-recording / MediaProjection
     * consent needed) and saves it to Pictures/RecorderZy. The result URI is
     * delivered on a background thread - callers should marshal back to their
     * own thread if needed.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(scalePercent: Int, onResult: (Uri?) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val uri = try {
                            saveScreenshot(screenshot, scalePercent)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to save screenshot: ${t.message}", t)
                            null
                        } finally {
                            executor.shutdown()
                        }
                        onResult(uri)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed, errorCode=$errorCode")
                        executor.shutdown()
                        onResult(null)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot threw: ${t.message}", t)
            runCatching { executor.shutdown() }
            onResult(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun saveScreenshot(screenshot: ScreenshotResult, scalePercent: Int): Uri? {
        val hardwareBuffer = screenshot.hardwareBuffer
        try {
            val raw = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return null
            // Copy out of the hardware buffer into a software bitmap we can
            // compress, then release the hardware bitmap.
            var bmp = raw.copy(Bitmap.Config.ARGB_8888, false)
            raw.recycle()
            if (bmp == null) return null

            if (scalePercent in 25..99) {
                val nw = (bmp.width * scalePercent / 100).coerceAtLeast(1)
                val nh = (bmp.height * scalePercent / 100).coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }

            val baos = ByteArrayOutputStream(256 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            bmp.recycle()

            return MediaStoreWriter.writeImage(
                applicationContext,
                baos.toByteArray(),
                "RecorderZy-${System.currentTimeMillis()}",
                "image/jpeg"
            )
        } finally {
            runCatching { hardwareBuffer.close() }
        }
    }
}
