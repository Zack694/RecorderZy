package com.recorderzy.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.recorderzy.app.R
import com.recorderzy.app.service.ScreenRecordService

/**
 * Builds the persistent foreground notification that Android 14+ requires for
 * any [android.app.Service] running with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 * and FOREGROUND_SERVICE_TYPE_MICROPHONE.
 *
 * The notification carries quick-actions for Pause / Resume / Stop, all of
 * which round-trip through [ScreenRecordService] (no Activity in the loop) so
 * they fire instantly even when the user is in another app.
 */
object RecorderNotifications {

    const val CHANNEL_RECORDING = "recorderzy.recording"
    const val NOTIF_ID_RECORDING = 0xFA17

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            context.getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_recording_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun buildRecordingNotification(
        context: Context,
        isPaused: Boolean,
        elapsedMs: Long,
    ): Notification {
        val mins = elapsedMs / 60_000
        val secs = (elapsedMs / 1000) % 60
        val timer = "%02d:%02d".format(mins, secs)

        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (isPaused) context.getString(R.string.notif_title_paused)
                else context.getString(R.string.notif_title_recording)
            )
            .setContentText("RecorderZy · $timer")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isPaused) {
            builder.addAction(
                R.drawable.ic_play_white,
                context.getString(R.string.notif_action_resume),
                pendingService(context, ScreenRecordService.ACTION_RESUME)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_white,
                context.getString(R.string.notif_action_pause),
                pendingService(context, ScreenRecordService.ACTION_PAUSE)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_white,
            context.getString(R.string.notif_action_stop),
            pendingService(context, ScreenRecordService.ACTION_STOP)
        )
        return builder.build()
    }

    private fun pendingService(context: Context, action: String): PendingIntent {
        val intent = Intent(context, ScreenRecordService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
