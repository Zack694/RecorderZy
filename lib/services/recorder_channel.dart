import 'dart:async';

import 'package:flutter/services.dart';

import '../models/recording_settings.dart';

/// Type-safe wrapper around the four MethodChannels exposed by the native
/// Kotlin layer (see `MethodChannels.kt`). Pure async/await – every call
/// surfaces native errors as Dart [PlatformException]s the UI can catch.
class RecorderChannel {
  RecorderChannel() {
    _recorderCh.setMethodCallHandler(_handleRecorderEvent);
    _overlayCh.setMethodCallHandler(_handleOverlayEvent);
  }

  static const _recorderCh = MethodChannel('recorderzy/recorder');
  static const _overlayCh = MethodChannel('recorderzy/overlay');
  static const _permissionsCh = MethodChannel('recorderzy/permissions');
  static const _performanceCh = MethodChannel('recorderzy/performance');

  // Streams the in-app UI subscribes to so the home screen mirrors live state.
  final _state = StreamController<RecorderEvent>.broadcast();
  Stream<RecorderEvent> get events => _state.stream;

  // ============= Recorder =============
  Future<bool> startRecording(RecordingSettings settings) async {
    try {
      final ok = await _recorderCh.invokeMethod<bool>(
            'startRecording',
            settings.toNativeMap(),
          ) ??
          false;
      return ok;
    } on PlatformException {
      return false;
    }
  }

  Future<void> pauseRecording() async =>
      _recorderCh.invokeMethod('pauseRecording');

  Future<void> resumeRecording() async =>
      _recorderCh.invokeMethod('resumeRecording');

  Future<void> stopRecording() async =>
      _recorderCh.invokeMethod('stopRecording');

  Future<bool> isRecording() async =>
      (await _recorderCh.invokeMethod<bool>('isRecording')) ?? false;

  Future<String?> captureScreenshot(double scale) async {
    return _recorderCh.invokeMethod<String>(
      'captureScreenshot',
      {'scale': scale},
    );
  }

  Future<List<Map<Object?, Object?>>> listRecordings() async {
    final result = await _recorderCh
        .invokeMethod<List<Object?>>('listRecordings');
    return result?.cast<Map<Object?, Object?>>() ?? const [];
  }

  Future<void> updateLiveSettings(Map<String, Object?> diff) async {
    await _recorderCh.invokeMethod('updateLiveSettings', diff);
  }

  // ============= Overlay =============
  Future<void> showFloatingBubble() =>
      _overlayCh.invokeMethod('showFloatingBubble');

  Future<void> hideFloatingBubble() =>
      _overlayCh.invokeMethod('hideFloatingBubble');

  Future<void> configureBubble({required int sizeDp, required double alpha}) =>
      _overlayCh.invokeMethod('configureBubble', {
        'sizeDp': sizeDp,
        'alpha': alpha,
      });

  Future<void> showTouchIndicator() =>
      _overlayCh.invokeMethod('showTouchIndicator');

  Future<void> hideTouchIndicator() =>
      _overlayCh.invokeMethod('hideTouchIndicator');

  // ============= Permissions =============
  Future<bool> canDrawOverlays() async =>
      (await _permissionsCh.invokeMethod<bool>('canDrawOverlays')) ?? false;

  Future<void> openOverlaySettings() =>
      _permissionsCh.invokeMethod('openOverlaySettings');

  Future<bool> isIgnoringBatteryOptimizations() async =>
      (await _permissionsCh.invokeMethod<bool>(
            'isIgnoringBatteryOptimizations',
          )) ??
          false;

  Future<void> requestIgnoreBatteryOptimizations() =>
      _permissionsCh.invokeMethod('requestIgnoreBatteryOptimizations');

  Future<void> openAppNotificationSettings() =>
      _permissionsCh.invokeMethod('openAppNotificationSettings');

  // ============= Performance =============
  Future<double> thermalHeadroom() async =>
      (await _performanceCh.invokeMethod<double>('getThermalHeadroom')) ?? 1.0;

  Future<double?> cpuHeadroom() async =>
      _performanceCh.invokeMethod<double>('getCpuHeadroom');

  Future<double?> gpuHeadroom() async =>
      _performanceCh.invokeMethod<double>('getGpuHeadroom');

  Future<bool> hasArrSupport() async =>
      (await _performanceCh.invokeMethod<bool>('hasArrSupport')) ?? false;

  Future<double> suggestedFrameRate(int category) async =>
      (await _performanceCh.invokeMethod<double>(
        'getSuggestedFrameRate',
        {'category': category},
      )) ??
      60.0;

  Future<Map<String, Object?>> deviceInfo() async {
    final result = await _performanceCh
        .invokeMapMethod<String, Object?>('deviceInfo');
    return result ?? const <String, Object?>{};
  }

  // ============= Event router =============
  Future<dynamic> _handleRecorderEvent(MethodCall call) async {
    switch (call.method) {
      case 'onRecordingStarted':
        _state.add(RecorderEvent.started(
          uri: (call.arguments as Map?)?['uri'] as String?,
        ));
        break;
      case 'onRecordingStopped':
        _state.add(RecorderEvent.stopped(
          uri: (call.arguments as Map?)?['uri'] as String?,
        ));
        break;
      case 'onPaused':
        _state.add(const RecorderEvent.paused());
        break;
      case 'onResumed':
        _state.add(const RecorderEvent.resumed());
        break;
      case 'onTick':
        final args = call.arguments as Map?;
        _state.add(RecorderEvent.tick(
          elapsedMs: (args?['elapsedMs'] as num?)?.toInt() ?? 0,
          paused: (args?['paused'] as bool?) ?? false,
        ));
        break;
    }
    return null;
  }

  Future<dynamic> _handleOverlayEvent(MethodCall call) async => null;

  void dispose() {
    _state.close();
  }
}

class RecorderEvent {
  const RecorderEvent._(this.kind, {this.uri, this.elapsedMs = 0, this.paused = false});

  final String kind;
  final String? uri;
  final int elapsedMs;
  final bool paused;

  const RecorderEvent.started({String? uri}) : this._('started', uri: uri);
  const RecorderEvent.stopped({String? uri}) : this._('stopped', uri: uri);
  const RecorderEvent.paused() : this._('paused', paused: true);
  const RecorderEvent.resumed() : this._('resumed');
  const RecorderEvent.tick({required int elapsedMs, required bool paused})
      : this._('tick', elapsedMs: elapsedMs, paused: paused);
}
