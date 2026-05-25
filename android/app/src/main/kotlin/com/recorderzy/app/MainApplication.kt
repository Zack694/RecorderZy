package com.recorderzy.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MainApplication : Application() {

    companion object {
        const val CHANNEL_RECORDING = "recorderzy.recording"
        const val CHANNEL_OVERLAY = "recorderzy.overlay"
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_recording_desc)
                setShowBadge(false)
            }
        )

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notif_channel_overlay_desc)
                setShowBadge(false)
            }
        )
    }
}
