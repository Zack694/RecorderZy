import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'settings_model.dart';

/// Listenable wrapper around [SharedPreferences] that drives the whole
/// configuration UI. Mutations are synchronous in-memory and async to disk
/// so the UI never waits on storage I/O.
class SettingsController extends ChangeNotifier {
  RecorderSettings _value = const RecorderSettings();
  RecorderSettings get value => _value;

  SharedPreferences? _prefs;

  static const _keyQuality = 'qualityPreset';
  static const _keyBitrate = 'bitrateMbps';
  static const _keyFps = 'frameRate';
  static const _keyApv = 'useApv';
  static const _keyAudio = 'audioMode';
  static const _keyNs = 'noiseSuppression';
  static const _keyVoice = 'voicePreset';
  static const _keyTouches = 'showTouches';
  static const _keyShot = 'screenshotScale';
  static const _keyOvSize = 'overlaySizeDp';
  static const _keyOvAlpha = 'overlayAlpha';
  static const _keyPrimary = 'primaryColor';
  static const _keyAccent = 'accentColor';
  static const _keyBackground = 'backgroundColor';
  static const _keySurfaceAlpha = 'surfaceAlpha';

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    _prefs = prefs;
    _value = RecorderSettings(
      qualityPreset: _readEnum(prefs, _keyQuality, QualityPreset.values, QualityPreset.balanced),
      bitrateMbps: prefs.getInt(_keyBitrate) ?? 12,
      frameRate: prefs.getInt(_keyFps) ?? 60,
      useApv: prefs.getBool(_keyApv) ?? false,
      audioMode: _readEnum(prefs, _keyAudio, AudioMode.values, AudioMode.mic),
      noiseSuppression: prefs.getBool(_keyNs) ?? false,
      voicePreset: _readEnum(prefs, _keyVoice, VoicePreset.values, VoicePreset.normal),
      showTouches: prefs.getBool(_keyTouches) ?? false,
      screenshotScale: _readEnum(
        prefs,
        _keyShot,
        ScreenshotScale.values,
        ScreenshotScale.full,
      ),
      overlaySizeDp: prefs.getInt(_keyOvSize) ?? 56,
      overlayAlpha: prefs.getDouble(_keyOvAlpha) ?? 0.92,
      primaryColor: Color(prefs.getInt(_keyPrimary) ?? 0xFF5C6BC0),
      accentColor: Color(prefs.getInt(_keyAccent) ?? 0xFFFF7043),
      backgroundColor: Color(prefs.getInt(_keyBackground) ?? 0xFF101218),
      surfaceAlpha: prefs.getDouble(_keySurfaceAlpha) ?? 0.92,
    );
    notifyListeners();
  }

  void update(RecorderSettings Function(RecorderSettings prev) mutate) {
    _value = mutate(_value);
    notifyListeners();
    _persist();
  }

  Future<void> _persist() async {
    final prefs = _prefs ?? await SharedPreferences.getInstance();
    _prefs = prefs;
    await prefs.setString(_keyQuality, _value.qualityPreset.name);
    await prefs.setInt(_keyBitrate, _value.bitrateMbps);
    await prefs.setInt(_keyFps, _value.frameRate);
    await prefs.setBool(_keyApv, _value.useApv);
    await prefs.setString(_keyAudio, _value.audioMode.name);
    await prefs.setBool(_keyNs, _value.noiseSuppression);
    await prefs.setString(_keyVoice, _value.voicePreset.name);
    await prefs.setBool(_keyTouches, _value.showTouches);
    await prefs.setString(_keyShot, _value.screenshotScale.name);
    await prefs.setInt(_keyOvSize, _value.overlaySizeDp);
    await prefs.setDouble(_keyOvAlpha, _value.overlayAlpha);
    // ignore: deprecated_member_use
    await prefs.setInt(_keyPrimary, _value.primaryColor.value);
    // ignore: deprecated_member_use
    await prefs.setInt(_keyAccent, _value.accentColor.value);
    // ignore: deprecated_member_use
    await prefs.setInt(_keyBackground, _value.backgroundColor.value);
    await prefs.setDouble(_keySurfaceAlpha, _value.surfaceAlpha);
  }

  T _readEnum<T extends Enum>(
    SharedPreferences prefs,
    String key,
    List<T> values,
    T fallback,
  ) {
    final raw = prefs.getString(key);
    if (raw == null) return fallback;
    return values.firstWhere(
      (v) => v.name == raw,
      orElse: () => fallback,
    );
  }
}
