package com.recorderzy.app.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream

/**
 * MediaStore-backed storage. Saves all captures to the public gallery under the
 * `RecorderZy` album, fully compliant with Android 16's Scoped Storage rules
 * (no WRITE_EXTERNAL_STORAGE permission required).
 *
 * Returned [Uri] values are content URIs the user can hand to any media viewer
 * — they survive uninstalls and are visible in the system Photos / Gallery app.
 */
object MediaStoreHelper {

    private const val ALBUM = "RecorderZy"
    private const val VIDEO_RELATIVE = "Movies/$ALBUM"
    private const val IMAGE_RELATIVE = "Pictures/$ALBUM"

    /**
     * Opens a MediaStore-managed video file for writing and returns both the
     * URI and a [ParcelFileDescriptor] that the [com.recorderzy.app.recorder.ScreenRecorder]
     * can hand straight to MediaRecorder / MediaMuxer.
     */
    fun openVideoForWrite(context: Context, displayName: String, mimeType: String = "video/mp4"): Pair<Uri, ParcelFileDescriptor> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_RELATIVE)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION") MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("openFileDescriptor failed")
        return uri to pfd
    }

    /**
     * Marks a previously [openVideoForWrite]-returned URI as no longer pending so
     * the gallery picks it up. Should be called after the encoder has stopped.
     */
    fun finaliseVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    /** Atomic "compose & write a PNG" used by [com.recorderzy.app.recorder.ScreenshotCapture]. */
    fun writeImage(context: Context, bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val displayName = "RecorderZy_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, IMAGE_RELATIVE)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        val out: OutputStream = resolver.openOutputStream(uri) ?: error("openOutputStream failed")
        out.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, finalize, null, null)
        }
        return uri
    }

    /** Lists everything in the RecorderZy album for the in-app library tab. */
    fun listAlbumItems(context: Context): List<Map<String, Any?>> {
        val resolver = context.contentResolver
        val out = mutableListOf<Map<String, Any?>>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return out

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )

        val videoUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val imageUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        for (collection in listOf(videoUri, imageUri)) {
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("%/$ALBUM/%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out += mapOf(
                        "uri" to "$collection/$id",
                        "name" to c.getString(nameCol),
                        "size" to c.getLong(sizeCol),
                        "dateAdded" to c.getLong(dateCol),
                        "mimeType" to c.getString(mimeCol),
                    )
                }
            }
        }
        return out
    }
}
