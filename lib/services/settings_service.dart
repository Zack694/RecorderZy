import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/recording_settings.dart';

/// SharedPreferences-backed settings store that broadcasts mutations via
/// [ChangeNotifier]. Read-once, write-coalesced – every setter persists to
/// disk asynchronously so the UI thread never blocks on IO.
class SettingsService extends ChangeNotifier {
  SettingsService(this._prefs);

  static const _key = 'recorderzy.settings.v1';
  final SharedPreferences _prefs;

  RecordingSettings _settings = RecordingSettings.defaults();
  RecordingSettings get settings => _settings;

  void load() {
    final raw = _prefs.getString(_key);
    if (raw == null) return;
    try {
      _settings = RecordingSettings.fromJson(
        jsonDecode(raw) as Map<String, Object?>,
      );
      notifyListeners();
    } catch (_) {
      _settings = RecordingSettings.defaults();
    }
  }

  Future<void> _save() async {
    await _prefs.setString(_key, jsonEncode(_settings.toJson()));
  }

  // ----- Setters: each notifies + persists -----
  void setBitrate(int bps) {
    _settings.bitrateBps = bps;
    notifyListeners();
    _save();
  }

  void setFrameRate(int fps) {
    _settings.frameRate = fps;
    notifyListeners();
    _save();
  }

  void setQualityPreset(QualityPreset preset) {
    _settings.qualityPreset = preset;
    if (preset != QualityPreset.custom) {
      _settings.bitrateBps = preset.bitrate;
    }
    notifyListeners();
    _save();
  }

  void setUseApv(bool v) {
    _settings.useApvCodec = v;
    notifyListeners();
    _save();
  }

  void setAudioMode(AudioMode mode) {
    _settings.audioMode = mode;
    notifyListeners();
    _save();
  }

  void setNoiseSuppression(bool v) {
    _settings.noiseSuppression = v;
    notifyListeners();
    _save();
  }

  void setVoicePreset(VoicePreset p) {
    _settings.voicePreset = p;
    notifyListeners();
    _save();
  }

  void setShowTouches(bool v) {
    _settings.showTouches = v;
    notifyListeners();
    _save();
  }

  void setScreenshotScale(double s) {
    _settings.screenshotScale = s;
    notifyListeners();
    _save();
  }

  void setBubbleSize(int dp) {
    _settings.bubbleSizeDp = dp;
    notifyListeners();
    _save();
  }

  void setBubbleAlpha(double a) {
    _settings.bubbleAlpha = a;
    notifyListeners();
    _save();
  }

  void setMuxerFlush(int s) {
    _settings.muxerFlushSeconds = s;
    notifyListeners();
    _save();
  }

  void setMatchPanelArr(bool v) {
    _settings.matchPanelArr = v;
    notifyListeners();
    _save();
  }
}
