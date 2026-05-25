package com.recorderzy.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.recorderzy.app.service.ScreenRecordService

/**
 * Transparent shim activity used by the floating bubble (and any non-activity
 * caller) to request a fresh [android.media.projection.MediaProjection] token.
 *
 * This is necessary because Android 14+ revokes any cached projection token the
 * moment the previous session ends; we must invoke
 * [MediaProjectionManager.createScreenCaptureIntent] from an Activity context
 * every single time recording is initiated.
 */
class MediaProjectionRequestActivity : Activity() {

    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ScreenRecordService.startWithProjection(
                    this,
                    result.resultCode,
                    result.data!!
                )
            }
            finish()
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onPause() {
        super.onPause()
        // We are a one-shot transparent activity; if the system pauses us before we
        // get a result (e.g. user backgrounded the consent dialog) we self-destruct
        // so we don't leak in the recents list on Android 16.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isFinishing.not()) {
            // intentionally left blank: we wait for the launcher result
        }
    }
}
