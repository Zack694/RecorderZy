package com.recorderzy.app

import android.os.Build
import android.os.Bundle
import androidx.core.view.WindowCompat
import com.recorderzy.app.channels.OverlayChannel
import com.recorderzy.app.channels.RecorderChannel
import com.recorderzy.app.channels.SettingsChannel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * Hosts the Flutter UI and wires up the platform channels that drive the
 * native recorder, floating overlay and settings layer.
 *
 * Android 16 mandates edge-to-edge layouts; we honour that by disabling the
 * decor-fitting behaviour and letting Flutter consume system insets via
 * `MediaQuery.of(context).viewPadding` / `SafeArea` in the Dart layer.
 */
class MainActivity : FlutterActivity() {

    private lateinit var recorderChannel: RecorderChannel
    private lateinit var overlayChannel: OverlayChannel
    private lateinit var settingsChannel: SettingsChannel

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: required on Android 15+ and enforced on Android 16.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger

        recorderChannel = RecorderChannel(this, messenger).also { it.attach() }
        overlayChannel = OverlayChannel(this, messenger).also { it.attach() }
        settingsChannel = SettingsChannel(this, messenger).also { it.attach() }
    }

    override fun onDestroy() {
        runCatching { recorderChannel.detach() }
        runCatching { overlayChannel.detach() }
        runCatching { settingsChannel.detach() }
        super.onDestroy()
    }
}
