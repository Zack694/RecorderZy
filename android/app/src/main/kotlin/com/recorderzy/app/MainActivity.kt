package com.recorderzy.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.recorderzy.app.channels.MethodChannels
import com.recorderzy.app.recorder.RecordingSession
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 enforces edge-to-edge. Ask the framework to draw behind the
        // system bars and let the Flutter layer handle insets via SafeArea.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            MethodChannels.onProjectionResult(this, result.resultCode, result.data)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannels.register(
            context = this,
            messenger = flutterEngine.dartExecutor.binaryMessenger,
            requestProjection = ::launchProjectionConsent
        )
    }

    /**
     * Android 14+ requires a fresh user-granted MediaProjection token for every recording
     * session. We launch [MediaProjectionManager.createScreenCaptureIntent] each time the
     * Flutter UI (or the floating bubble proxying through here) starts a session.
     */
    private fun launchProjectionConsent() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        // Recording sessions are owned by ScreenRecordService – they outlive this activity.
        // We only release the activity-scoped channel handlers here.
        MethodChannels.unregister()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        @Suppress("DEPRECATION")
        fun resolveBuildName(): String =
            "Android ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"

        // Convenience accessor used by other modules that just need to know whether
        // the active app is currently recording.
        fun isRecording(): Boolean = RecordingSession.instance?.isActive == true
    }
}
