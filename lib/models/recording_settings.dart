// Settings model that mirrors the [RecordingSession.Settings] payload on the
// native side. Kept in lock-step with `RecordingSession.kt` -> `fromMap()`.

enum AudioMode {
  mute('mute', 'Mute'),
  micOnly('mic_only', 'Microphone only'),
  internalOnly('internal_only', 'Internal / system only'),
  micAndInternal('mic_and_internal', 'Microphone + system');

  const AudioMode(this.id, this.label);
  final String id;
  final String label;

  static AudioMode fromId(String id) =>
      AudioMode.values.firstWhere((e) => e.id == id, orElse: () => micAndInternal);
}

enum VoicePreset {
  normal('normal', 'Normal'),
  deep('deep', 'Deep voice'),
  robotic('robotic', 'Robotic'),
  helium('helium', 'Helium / chipmunk'),
  radio('radio', 'Radio comms');

  const VoicePreset(this.id, this.label);
  final String id;
  final String label;

  static VoicePreset fromId(String id) =>
      VoicePreset.values.firstWhere((e) => e.id == id, orElse: () => normal);
}

enum QualityPreset {
  low('low', 'Low (4 Mbps)', 4_000_000),
  medium('medium', 'Medium (8 Mbps)', 8_000_000),
  high('high', 'High (12 Mbps)', 12_000_000),
  veryHigh('very_high', 'Very High (20 Mbps)', 20_000_000),
  extreme('extreme', 'Extreme (40 Mbps)', 40_000_000),
  custom('custom', 'Custom', 0);

  const QualityPreset(this.id, this.label, this.bitrate);
  final String id;
  final String label;
  final int bitrate;

  static QualityPreset fromId(String id) =>
      QualityPreset.values.firstWhere((e) => e.id == id, orElse: () => high);
}

class RecordingSettings {
  RecordingSettings({
    required this.bitrateBps,
    required this.frameRate,
    required this.qualityPreset,
    required this.useApvCodec,
    required this.audioMode,
    required this.noiseSuppression,
    required this.voicePreset,
    required this.showTouches,
    required this.screenshotScale,
    required this.bubbleSizeDp,
    required this.bubbleAlpha,
    required this.muxerFlushSeconds,
    required this.matchPanelArr,
  });

  factory RecordingSettings.defaults() => RecordingSettings(
        bitrateBps: 12_000_000,
        frameRate: 60,
        qualityPreset: QualityPreset.high,
        useApvCodec: false,
        audioMode: AudioMode.micAndInternal,
        noiseSuppression: true,
        voicePreset: VoicePreset.normal,
        showTouches: false,
        screenshotScale: 1.0,
        bubbleSizeDp: 64,
        bubbleAlpha: 0.95,
        muxerFlushSeconds: 2,
        matchPanelArr: true,
      );

  int bitrateBps;
  int frameRate; // 30, 60, 90, 120
  QualityPreset qualityPreset;
  bool useApvCodec;
  AudioMode audioMode;
  bool noiseSuppression;
  VoicePreset voicePreset;
  bool showTouches;
  double screenshotScale; // 0.5, 0.75, 1.0
  int bubbleSizeDp;
  double bubbleAlpha;
  int muxerFlushSeconds;
  bool matchPanelArr;

  Map<String, Object?> toNativeMap() => {
        'bitrateBps': bitrateBps,
        'frameRate': frameRate,
        'useApvCodec': useApvCodec,
        'audioMode': audioMode.id,
        'noiseSuppression': noiseSuppression,
        'voicePreset': voicePreset.id,
        'showTouches': showTouches,
        'muxerFlushSeconds': muxerFlushSeconds,
      };

  Map<String, Object?> toJson() => {
        'bitrateBps': bitrateBps,
        'frameRate': frameRate,
        'qualityPreset': qualityPreset.id,
        'useApvCodec': useApvCodec,
        'audioMode': audioMode.id,
        'noiseSuppression': noiseSuppression,
        'voicePreset': voicePreset.id,
        'showTouches': showTouches,
        'screenshotScale': screenshotScale,
        'bubbleSizeDp': bubbleSizeDp,
        'bubbleAlpha': bubbleAlpha,
        'muxerFlushSeconds': muxerFlushSeconds,
        'matchPanelArr': matchPanelArr,
      };

  factory RecordingSettings.fromJson(Map<String, Object?> j) {
    final defaults = RecordingSettings.defaults();
    return RecordingSettings(
      bitrateBps: (j['bitrateBps'] as int?) ?? defaults.bitrateBps,
      frameRate: (j['frameRate'] as int?) ?? defaults.frameRate,
      qualityPreset: QualityPreset.fromId(
          (j['qualityPreset'] as String?) ?? defaults.qualityPreset.id),
      useApvCodec: (j['useApvCodec'] as bool?) ?? defaults.useApvCodec,
      audioMode: AudioMode.fromId(
          (j['audioMode'] as String?) ?? defaults.audioMode.id),
      noiseSuppression:
          (j['noiseSuppression'] as bool?) ?? defaults.noiseSuppression,
      voicePreset: VoicePreset.fromId(
          (j['voicePreset'] as String?) ?? defaults.voicePreset.id),
      showTouches: (j['showTouches'] as bool?) ?? defaults.showTouches,
      screenshotScale:
          (j['screenshotScale'] as num?)?.toDouble() ?? defaults.screenshotScale,
      bubbleSizeDp: (j['bubbleSizeDp'] as int?) ?? defaults.bubbleSizeDp,
      bubbleAlpha:
          (j['bubbleAlpha'] as num?)?.toDouble() ?? defaults.bubbleAlpha,
      muxerFlushSeconds:
          (j['muxerFlushSeconds'] as int?) ?? defaults.muxerFlushSeconds,
      matchPanelArr:
          (j['matchPanelArr'] as bool?) ?? defaults.matchPanelArr,
    );
  }
}
