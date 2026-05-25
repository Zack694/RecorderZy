package com.recorderzy.app.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Helpers for publishing video & screenshot output to the public gallery via
 * `MediaStore`.
 *
 * Everything lands in `Movies/RecorderZy` and `Pictures/RecorderZy` so the
 * user's stock gallery picks the album up automatically. We rely on Scoped
 * Storage primitives - no MANAGE_EXTERNAL_STORAGE, no legacy
 * `getExternalStoragePublicDirectory` shenanigans.
 */
object MediaStoreWriter {

    private const val ALBUM = "RecorderZy"

    fun newVideoUri(context: Context, displayName: String): Uri? {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ensureExt(displayName, ".mp4"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + ALBUM
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return resolver.insert(collection, values)
    }

    fun finalizeVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val update = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        runCatching { resolver.update(uri, update, null, null) }
    }

    /**
     * Writes a JPEG/PNG byte array as an image into Pictures/RecorderZy and
     * returns the generated content URI.
     */
    fun writeImage(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mime: String = "image/jpeg",
    ): Uri? {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val ext = if (mime == "image/png") ".png" else ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ensureExt(displayName, ext))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { resolver.update(uri, update, null, null) }
        }
        return uri
    }

    /**
     * Streams a backing temp file into the supplied MediaStore URI. Used after
     * `MediaMuxer` is closed so we can move the partially-flushed mp4 into the
     * public collection without holding it open during recording.
     */
    fun copyFileTo(context: Context, src: File, target: Uri) {
        context.contentResolver.openOutputStream(target, "w")?.use { out ->
            FileInputStream(src).use { input ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }
        }
    }

    private fun ensureExt(name: String, ext: String): String =
        if (name.endsWith(ext, ignoreCase = true)) name else name + ext
}
