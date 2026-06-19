import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import '../settings/settings_model.dart';

/// Thin wrapper around the four platform channels exposed by the Kotlin side.
///
/// The bridge is intentionally stateless apart from the [recorderState] stream
/// - the `SettingsController` owns persisted settings, this class just shuttles
/// calls and parses event-channel payloads.
class RecorderBridge {
  RecorderBridge._();
  static final RecorderBridge instance = RecorderBridge._();

  static const _recorderChannel = MethodChannel('recorderzy/recorder');
  static const _overlayChannel = MethodChannel('recorderzy/overlay');
  static const _settingsChannel = MethodChannel('recorderzy/settings');
  static const _stateEvents = EventChannel('recorderzy/recorder/state');

  Stream<RecorderState>? _stateStream;
  Stream<RecorderState> get recorderState =>
      _stateStream ??= _stateEvents.receiveBroadcastStream().map((event) {
        final map = (event as Map?)?.cast<dynamic, dynamic>() ?? const {};
        return RecorderState(
          phase: _phaseFrom(map['phase'] as String? ?? 'IDLE'),
          elapsedMs: (map['elapsedMs'] as num?)?.toInt() ?? 0,
        );
      });

  // -- Recorder ---------------------------------------------------------------

  Future<bool> startRecording({
    required RecorderSettings settings,
    required DisplayInfo display,
  }) async {
    try {
      final ok = await _recorderChannel.invokeMethod<bool>(
        'startRecording',
        {'config': _buildConfig(settings, display)},
      );
      return ok ?? false;
    } catch (e) {
      print('RecorderBridge.startRecording error: $e');
      return false;
    }
  }

  Future<void> pauseRecording() async {
    try {
      await _recorderChannel.invokeMethod<void>('pauseRecording');
    } catch (e) {
      print('RecorderBridge.pauseRecording error: $e');
    }
  }

  Future<void> resumeRecording() async {
    try {
      await _recorderChannel.invokeMethod<void>('resumeRecording');
    } catch (e) {
      print('RecorderBridge.resumeRecording error: $e');
    }
  }

  Future<void> stopRecording() async {
    try {
      await _recorderChannel.invokeMethod<void>('stopRecording');
    } catch (e) {
      print('RecorderBridge.stopRecording error: $e');
    }
  }

  Future<bool> takeScreenshot({
    required RecorderSettings settings,
    required DisplayInfo display,
  }) async {
    try {
      final ok = await _recorderChannel.invokeMethod<bool>(
        'takeScreenshot',
        {
          'config': _buildConfig(settings, display),
          'scalePercent': settings.screenshotScale.percent,
        },
      );
      return ok ?? false;
    } catch (e) {
      print('RecorderBridge.takeScreenshot error: $e');
      return false;
    }
  }

  Future<bool> isRecording() async {
    final v = await _recorderChannel.invokeMethod<bool>('isRecording');
    return v ?? false;
  }

  // -- Overlay ----------------------------------------------------------------

  Future<bool> overlayHasPermission() async =>
      (await _overlayChannel.invokeMethod<bool>('hasPermission')) ?? false;

  Future<void> overlayRequestPermission() =>
      _overlayChannel.invokeMethod<void>('requestPermission');

  Future<void> showOverlay() =>
      _overlayChannel.invokeMethod<void>('showOverlay');

  Future<void> hideOverlay() =>
      _overlayChannel.invokeMethod<void>('hideOverlay');

  Future<void> setOverlayStyle({required int sizeDp, required double alpha}) =>
      _overlayChannel.invokeMethod<void>('setStyle', {
        'sizeDp': sizeDp,
        'alpha': alpha,
      });

  // -- Settings / system ------------------------------------------------------

  Future<DisplayInfo> displayMetrics() async {
    final raw = await _settingsChannel.invokeMethod<Map<dynamic, dynamic>>(
      'getDisplayMetrics',
    );
    final m = raw?.cast<String, dynamic>() ?? const {};
    return DisplayInfo(
      widthPx: (m['widthPx'] as num?)?.toInt() ?? 1080,
      heightPx: (m['heightPx'] as num?)?.toInt() ?? 1920,
      densityDpi: (m['densityDpi'] as num?)?.toInt() ?? 420,
      refreshRateHz: (m['refreshRateHz'] as num?)?.toDouble() ?? 60.0,
    );
  }

  Future<bool> hasArrSupport() async =>
      (await _settingsChannel.invokeMethod<bool>('hasArrSupport')) ?? false;

  Future<int> getSuggestedFrameRate(int fallback) async {
    final v = await _settingsChannel.invokeMethod<int>(
      'getSuggestedFrameRate',
      {'fallback': fallback},
    );
    return v ?? fallback;
  }

  Future<bool> isIgnoringBatteryOptimizations() async =>
      (await _settingsChannel
              .invokeMethod<bool>('isIgnoringBatteryOptimizations')) ??
          false;

  Future<void> requestIgnoreBatteryOptimizations() =>
      _settingsChannel.invokeMethod<void>('requestIgnoreBatteryOptimizations');

  Future<void> openBatterySettings() =>
      _settingsChannel.invokeMethod<void>('openBatterySettings');

  // ---------------------------------------------------------------------------

  Map<String, dynamic> _buildConfig(
    RecorderSettings s,
    DisplayInfo display,
  ) {
    return {
      'widthPx': display.widthPx,
      'heightPx': display.heightPx,
      'densityDpi': display.densityDpi,
      'frameRate': s.frameRate,
      'bitrateBps': s.bitrateMbps * 1000000,
      'useApv': s.useApv,
      'audioMode': s.audioMode.nativeName,
      'noiseSuppression': s.noiseSuppression,
      'voicePreset': s.voicePreset.nativeName,
      'outputFileNameHint': 'RecorderZy',
    };
  }
}

RecorderPhase _phaseFrom(String name) {
  return switch (name) {
    'RECORDING' => RecorderPhase.recording,
    'PAUSED' => RecorderPhase.paused,
    _ => RecorderPhase.idle,
  };
}

enum RecorderPhase { idle, recording, paused }

@immutable
class RecorderState {
  const RecorderState({required this.phase, required this.elapsedMs});
  final RecorderPhase phase;
  final int elapsedMs;
}

@immutable
class DisplayInfo {
  const DisplayInfo({
    required this.widthPx,
    required this.heightPx,
    required this.densityDpi,
    required this.refreshRateHz,
  });
  final int widthPx;
  final int heightPx;
  final int densityDpi;
  final double refreshRateHz;
}
