package com.recorderzy.app.recorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * Captures a single still frame from a [MediaProjection] without disturbing
 * the active recording session. Output is JPEG-encoded and persisted via
 * [MediaStoreWriter].
 *
 * The capture is done on a dedicated handler thread so we never block the
 * Flutter UI (or the recorder's encoder loop) for the few milliseconds it
 * takes for the VirtualDisplay to push a frame.
 */
object Screenshotter {

    fun capture(
        context: Context,
        projection: MediaProjection,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        scalePercent: Int = 100,
        callback: (uri: android.net.Uri?) -> Unit,
    ) {
        val w = (widthPx * scalePercent / 100).coerceAtLeast(64)
        val h = (heightPx * scalePercent / 100).coerceAtLeast(64)

        val thread = HandlerThread("rzy-shot").apply { start() }
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        val display = projection.createVirtualDisplay(
            "RecorderZy-Shot",
            w, h, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface,
            null,
            handler
        )

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w

                val bitmap = Bitmap.createBitmap(
                    w + rowPadding / pixelStride,
                    h,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (rowPadding == 0) bitmap else
                    Bitmap.createBitmap(bitmap, 0, 0, w, h)

                val baos = ByteArrayOutputStream(64 * 1024)
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, baos)

                val uri = MediaStoreWriter.writeImage(
                    context,
                    baos.toByteArray(),
                    "RecorderZy-${System.currentTimeMillis()}",
                    "image/jpeg"
                )
                callback(uri)
            } finally {
                runCatching { image.close() }
                runCatching { display.release() }
                runCatching { reader.close() }
                runCatching { thread.quitSafely() }
            }
        }, handler)
    }
}
