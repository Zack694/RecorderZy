package com.recorderzy.app.recorder

import android.content.Intent

/**
 * Plain data container describing a single recording session. Built from the
 * persisted SharedPreferences settings on the Flutter side and forwarded
 * verbatim to [ScreenRecorderService].
 *
 * Kept minimal & immutable so it can be flattened into Intent extras without
 * needing Parcelable boilerplate.
 */
data class RecorderConfig(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val frameRate: Int,
    val bitrateBps: Int,
    val useApv: Boolean,
    val audioMode: AudioMode,
    val noiseSuppression: Boolean,
    val voicePreset: VoicePreset,
    val showTouches: Boolean,
    val outputFileNameHint: String,
) {
    enum class AudioMode { MUTE, MIC, INTERNAL, BOTH }
    enum class VoicePreset { NORMAL, DEEP, ROBOT, HELIUM, RADIO }

    fun applyExtras(intent: Intent): Intent = intent.apply {
        putExtra("widthPx", widthPx)
        putExtra("heightPx", heightPx)
        putExtra("densityDpi", densityDpi)
        putExtra("frameRate", frameRate)
        putExtra("bitrateBps", bitrateBps)
        putExtra("useApv", useApv)
        putExtra("audioMode", audioMode.name)
        putExtra("noiseSuppression", noiseSuppression)
        putExtra("voicePreset", voicePreset.name)
        putExtra("showTouches", showTouches)
        putExtra("outputFileNameHint", outputFileNameHint)
    }

    companion object {
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        fun fromMap(map: Map<*, *>): RecorderConfig = RecorderConfig(
            widthPx = (map["widthPx"] as? Int) ?: 1080,
            heightPx = (map["heightPx"] as? Int) ?: 1920,
            densityDpi = (map["densityDpi"] as? Int) ?: 420,
            frameRate = (map["frameRate"] as? Int) ?: 60,
            bitrateBps = (map["bitrateBps"] as? Int) ?: 12_000_000,
            useApv = (map["useApv"] as? Boolean) == true,
            audioMode = AudioMode.valueOf(
                (map["audioMode"] as? String)?.uppercase() ?: AudioMode.MIC.name
            ),
            noiseSuppression = (map["noiseSuppression"] as? Boolean) == true,
            voicePreset = VoicePreset.valueOf(
                (map["voicePreset"] as? String)?.uppercase() ?: VoicePreset.NORMAL.name
            ),
            showTouches = (map["showTouches"] as? Boolean) == true,
            outputFileNameHint = (map["outputFileNameHint"] as? String) ?: "RecorderZy",
        )

        fun fromIntent(intent: Intent): RecorderConfig = RecorderConfig(
            widthPx = intent.getIntExtra("widthPx", 1080),
            heightPx = intent.getIntExtra("heightPx", 1920),
            densityDpi = intent.getIntExtra("densityDpi", 420),
            frameRate = intent.getIntExtra("frameRate", 60),
            bitrateBps = intent.getIntExtra("bitrateBps", 12_000_000),
            useApv = intent.getBooleanExtra("useApv", false),
            audioMode = AudioMode.valueOf(
                intent.getStringExtra("audioMode") ?: AudioMode.MIC.name
            ),
            noiseSuppression = intent.getBooleanExtra("noiseSuppression", false),
            voicePreset = VoicePreset.valueOf(
                intent.getStringExtra("voicePreset") ?: VoicePreset.NORMAL.name
            ),
            showTouches = intent.getBooleanExtra("showTouches", false),
            outputFileNameHint = intent.getStringExtra("outputFileNameHint") ?: "RecorderZy",
        )
    }
}
