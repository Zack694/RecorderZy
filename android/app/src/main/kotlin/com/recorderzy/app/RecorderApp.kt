package com.recorderzy.app

import android.app.Application
import com.recorderzy.app.notification.RecorderNotifications
import io.flutter.app.FlutterApplication

/**
 * Application class. Initialised before any activity, used to:
 *  - Pre-create the persistent notification channel (so a foreground notification can
 *    be posted within ~5 seconds of any Service start, avoiding ANR).
 *  - Register a process-wide shutdown hook that lets [RecordingSession] flush the muxer
 *    if the OS terminates us mid-recording (crash recovery / graceful MP4 finalisation).
 */
class RecorderApp : FlutterApplication() {

    override fun onCreate() {
        super.onCreate()
        RecorderNotifications.ensureChannels(this)

        Runtime.getRuntime().addShutdownHook(Thread {
            // Best-effort: any in-flight recording will react to the abrupt termination
            // signal, stop the encoder, and finalise the MP4 moov atom before the
            // process is fully reclaimed.
            com.recorderzy.app.recorder.RecordingSession.instance?.emergencyFinalise()
        })
    }
}
