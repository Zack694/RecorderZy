import 'package:flutter/material.dart';

/// User-facing audio source selection.
enum AudioMode { mute, mic, internal, both }

extension AudioModeX on AudioMode {
  String get label => switch (this) {
        AudioMode.mute => 'Muted',
        AudioMode.mic => 'Microphone only',
        AudioMode.internal => 'Internal / system only',
        AudioMode.both => 'Mic + Internal (mixed)',
      };

  String get nativeName => switch (this) {
        AudioMode.mute => 'MUTE',
        AudioMode.mic => 'MIC',
        AudioMode.internal => 'INTERNAL',
        AudioMode.both => 'BOTH',
      };
}

/// Voice changer presets - 1:1 mirror of `RecorderConfig.VoicePreset` in Kotlin.
enum VoicePreset { normal, deep, robot, helium, radio }

extension VoicePresetX on VoicePreset {
  String get label => switch (this) {
        VoicePreset.normal => 'Normal',
        VoicePreset.deep => 'Deep voice',
        VoicePreset.robot => 'Robotic',
        VoicePreset.helium => 'Helium / Chipmunk',
        VoicePreset.radio => 'Radio comms',
      };

  String get nativeName => name.toUpperCase();
}

/// Coarse "quality" preset that drives bitrate + frame rate together.
enum QualityPreset { low, balanced, high, ultra }

extension QualityPresetX on QualityPreset {
  String get label => switch (this) {
        QualityPreset.low => 'Low (4 Mbps, 30 fps)',
        QualityPreset.balanced => 'Balanced (12 Mbps, 60 fps)',
        QualityPreset.high => 'High (24 Mbps, 60 fps)',
        QualityPreset.ultra => 'Ultra (40 Mbps, 120 fps)',
      };

  int get bitrateMbps => switch (this) {
        QualityPreset.low => 4,
        QualityPreset.balanced => 12,
        QualityPreset.high => 24,
        QualityPreset.ultra => 40,
      };

  int get frameRate => switch (this) {
        QualityPreset.low => 30,
        QualityPreset.balanced => 60,
        QualityPreset.high => 60,
        QualityPreset.ultra => 120,
      };
}

/// Screenshot resolution scaling preset.
enum ScreenshotScale { full, threeQuarter, half }

extension ScreenshotScaleX on ScreenshotScale {
  String get label => switch (this) {
        ScreenshotScale.full => '100% (native)',
        ScreenshotScale.threeQuarter => '75%',
        ScreenshotScale.half => '50%',
      };

  int get percent => switch (this) {
        ScreenshotScale.full => 100,
        ScreenshotScale.threeQuarter => 75,
        ScreenshotScale.half => 50,
      };
}

/// Persisted settings snapshot. `copyWith` lets the SettingsController publish
/// immutable instances over a Listenable so the UI can rebuild atomically.
class RecorderSettings {
  const RecorderSettings({
    this.qualityPreset = QualityPreset.balanced,
    this.bitrateMbps = 12,
    this.frameRate = 60,
    this.useApv = false,
    this.audioMode = AudioMode.mic,
    this.noiseSuppression = false,
    this.voicePreset = VoicePreset.normal,
    this.screenshotScale = ScreenshotScale.full,
    this.overlaySizeDp = 56,
    this.overlayAlpha = 0.92,
    this.primaryColor = const Color(0xFF5C6BC0),
    this.accentColor = const Color(0xFFFF7043),
    this.backgroundColor = const Color(0xFF101218),
    this.surfaceAlpha = 0.92,
  });

  final QualityPreset qualityPreset;
  final int bitrateMbps;
  final int frameRate;
  final bool useApv;
  final AudioMode audioMode;
  final bool noiseSuppression;
  final VoicePreset voicePreset;
  final ScreenshotScale screenshotScale;
  final int overlaySizeDp;
  final double overlayAlpha;
  final Color primaryColor;
  final Color accentColor;
  final Color backgroundColor;
  final double surfaceAlpha;

  RecorderSettings copyWith({
    QualityPreset? qualityPreset,
    int? bitrateMbps,
    int? frameRate,
    bool? useApv,
    AudioMode? audioMode,
    bool? noiseSuppression,
    VoicePreset? voicePreset,
    ScreenshotScale? screenshotScale,
    int? overlaySizeDp,
    double? overlayAlpha,
    Color? primaryColor,
    Color? accentColor,
    Color? backgroundColor,
    double? surfaceAlpha,
  }) {
    return RecorderSettings(
      qualityPreset: qualityPreset ?? this.qualityPreset,
      bitrateMbps: bitrateMbps ?? this.bitrateMbps,
      frameRate: frameRate ?? this.frameRate,
      useApv: useApv ?? this.useApv,
      audioMode: audioMode ?? this.audioMode,
      noiseSuppression: noiseSuppression ?? this.noiseSuppression,
      voicePreset: voicePreset ?? this.voicePreset,
      screenshotScale: screenshotScale ?? this.screenshotScale,
      overlaySizeDp: overlaySizeDp ?? this.overlaySizeDp,
      overlayAlpha: overlayAlpha ?? this.overlayAlpha,
      primaryColor: primaryColor ?? this.primaryColor,
      accentColor: accentColor ?? this.accentColor,
      backgroundColor: backgroundColor ?? this.backgroundColor,
      surfaceAlpha: surfaceAlpha ?? this.surfaceAlpha,
    );
  }
}
