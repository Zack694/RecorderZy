package com.recorderzy.app.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Translucent shim activity that requests a fresh MediaProjection token from
 * the system every time a recording session is initiated.
 *
 * Android 14+ invalidates a MediaProjection after a single VirtualDisplay
 * lifecycle, so the recorder must call [Intent] returned by
 * `MediaProjectionManager.createScreenCaptureIntent()` again per session.
 *
 * The result is broadcast back through a static callback registered by the
 * caller (the Flutter layer via RecorderChannel) so we don't need a bound
 * service or content provider.
 */
class ProjectionRequestActivity : ComponentActivity() {

    interface ResultListener {
        fun onProjectionGranted(resultCode: Int, data: Intent)
        fun onProjectionDenied()
    }

    companion object {
        @Volatile
        private var pendingListener: ResultListener? = null

        fun request(context: Context, listener: ResultListener) {
            pendingListener = listener
            val intent = Intent(context, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val listener = pendingListener
        pendingListener = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            listener?.onProjectionGranted(result.resultCode, result.data!!)
        } else {
            listener?.onProjectionDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            pendingListener?.onProjectionDenied()
            pendingListener = null
            finish()
            return
        }
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the activity is killed mid-flow without a result, propagate denial.
        pendingListener?.let {
            pendingListener = null
            it.onProjectionDenied()
        }
    }
}
