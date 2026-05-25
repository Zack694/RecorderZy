package com.recorderzy.app.recorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.recorderzy.app.storage.MediaStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-shot screenshot capture path. Re-uses an active [MediaProjection] when one
 * is already running for a recording session (so the user does not have to
 * re-grant consent), and otherwise the caller is expected to first request a
 * fresh consent token via [com.recorderzy.app.MediaProjectionRequestActivity].
 *
 * Output is scaled per the user's selected percentage in the Flutter
 * settings screen (100 / 75 / 50% etc.) before being written to MediaStore via
 * [MediaStoreHelper] under the "RecorderZy" album.
 */
object ScreenshotCapture {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun queueOneShot(
        context: Context,
        scale: Float,
        callback: (Uri?, Throwable?) -> Unit,
    ) {
        scope.launch {
            try {
                val projection = ProjectionStore.current
                    ?: run {
                        // We can still use the lighter-weight WindowManager surface dump
                        // route here, but it requires a recent projection. For now we
                        // surface a clean error so the Flutter UI can prompt the user.
                        callback(null, IllegalStateException("No active MediaProjection — start a session first or grant a one-shot token."))
                        return@launch
                    }
                val uri = capture(context, projection, scale.coerceIn(0.1f, 1f))
                callback(uri, null)
            } catch (t: Throwable) {
                callback(null, t)
            }
        }
    }

    private fun capture(context: Context, projection: MediaProjection, scale: Float): Uri {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display: Display = wm.defaultDisplay
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }

        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = metrics.densityDpi

        val handlerThread = HandlerThread("Screenshot").also { it.start() }
        val handler = Handler(handlerThread.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = projection.createVirtualDisplay(
            "RecorderZy-Shot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        return try {
            // Wait for the first frame.
            var bitmap: Bitmap? = null
            val deadline = System.currentTimeMillis() + 1500L
            while (bitmap == null && System.currentTimeMillis() < deadline) {
                reader.acquireLatestImage()?.use { image ->
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bmpW = width + rowPadding / pixelStride
                    val bmp = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    bitmap = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
                }
                if (bitmap == null) Thread.sleep(33L)
            }
            requireNotNull(bitmap) { "Failed to acquire screenshot frame within timeout" }
            MediaStoreHelper.writeImage(context, bitmap!!)
        } finally {
            virtualDisplay.release()
            reader.close()
            handlerThread.quitSafely()
        }
    }

    private fun android.media.Image.use(block: (android.media.Image) -> Unit) {
        try { block(this) } finally { close() }
    }
}

/**
 * Holds the in-flight [MediaProjection] so [ScreenshotCapture] can piggy-back on
 * it without re-prompting the user. Populated by
 * [com.recorderzy.app.service.ScreenRecordService] when a session begins.
 */
object ProjectionStore {
    @Volatile var current: MediaProjection? = null
}
