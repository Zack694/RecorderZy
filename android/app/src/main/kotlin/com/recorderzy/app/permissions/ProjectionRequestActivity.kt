package com.recorderzy.app.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
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
 * IMPORTANT: This activity must NOT use singleInstance launchMode because
 * registerForActivityResult doesn't work reliably across tasks on Xiaomi/
 * HyperOS/MIUI devices. We use "standard" launch mode and FLAG_ACTIVITY_NEW_TASK
 * only when launched from a non-Activity context.
 */
class ProjectionRequestActivity : ComponentActivity() {

    interface ResultListener {
        fun onProjectionGranted(resultCode: Int, data: Intent)
        fun onProjectionDenied()
    }

    companion object {
        private const val TAG = "ProjectionRequest"

        @Volatile
        var pendingListener: ResultListener? = null
            private set

        /**
         * Launch the projection request. Call from an Activity context when
         * possible (avoids FLAG_ACTIVITY_NEW_TASK which some OEMs handle
         * poorly with activity-result contracts).
         */
        fun request(context: Context, listener: ResultListener) {
            pendingListener = listener
            val intent = Intent(context, ProjectionRequestActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ProjectionRequestActivity: ${e.message}", e)
                pendingListener = null
                listener.onProjectionDenied()
            }
        }
    }

    private var launched = false

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
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guard against re-creation (e.g. config change) - if we already
        // launched the system dialog once this instance, don't do it again.
        if (savedInstanceState != null) {
            // Activity was recreated - the result contract will still fire
            // from the saved state, so just wait.
            launched = true
            return
        }

        if (launched) return
        launched = true

        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Log.e(TAG, "MediaProjectionManager is null")
            pendingListener?.onProjectionDenied()
            pendingListener = null
            finishAndRemoveTask()
            return
        }

        try {
            launcher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen capture intent: ${e.message}", e)
            pendingListener?.onProjectionDenied()
            pendingListener = null
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only propagate denial if we're actually finishing (not just a config change)
        if (isFinishing) {
            pendingListener?.let {
                pendingListener = null
                it.onProjectionDenied()
            }
        }
    }
}
