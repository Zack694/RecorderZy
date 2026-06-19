import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../core/platform/recorder_bridge.dart';
import '../core/settings/settings_controller.dart';
import '../core/settings/settings_model.dart';
import 'settings_screen.dart';
import 'widgets/animated_drawer.dart';
import 'widgets/section_card.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.settings});

  final SettingsController settings;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _bridge = RecorderBridge.instance;

  RecorderState _state = const RecorderState(phase: RecorderPhase.idle, elapsedMs: 0);
  DisplayInfo? _display;
  StreamSubscription<RecorderState>? _stateSub;

  bool _overlayPermission = false;
  bool _ignoresBattery = false;
  bool _accessibilityOn = false;
  bool _hasArr = false;
  int? _suggestedFps;

  @override
  void initState() {
    super.initState();
    _stateSub = _bridge.recorderState.listen((s) {
      if (mounted) setState(() => _state = s);
    });
    _refreshSystemFlags();
  }

  @override
  void dispose() {
    _stateSub?.cancel();
    super.dispose();
  }

  Future<void> _refreshSystemFlags() async {
    final results = await Future.wait([
      _bridge.displayMetrics(),
      _bridge.overlayHasPermission(),
      _bridge.isIgnoringBatteryOptimizations(),
      _bridge.isAccessibilityEnabled(),
      _bridge.hasArrSupport(),
      _bridge.getSuggestedFrameRate(widget.settings.value.frameRate),
    ]);
    if (!mounted) return;
    setState(() {
      _display = results[0] as DisplayInfo;
      _overlayPermission = results[1] as bool;
      _ignoresBattery = results[2] as bool;
      _accessibilityOn = results[3] as bool;
      _hasArr = results[4] as bool;
      _suggestedFps = results[5] as int;
    });
  }

  Future<void> _requestRuntimePermissions() async {
    await [
      Permission.microphone,
      Permission.notification,
    ].request();
    await _refreshSystemFlags();
  }

  Future<void> _startRecording() async {
    final settings = widget.settings.value;

    // Notifications are required for the foreground-service recording
    // notification on Android 13+. Request it up front.
    if (await Permission.notification.isDenied) {
      await Permission.notification.request();
    }

    // If the user picked an audio mode that needs the microphone, make sure
    // RECORD_AUDIO is granted BEFORE we start. On Android 14+ the recording
    // service can only declare the microphone foreground-service type when
    // this permission is held, otherwise audio is silently dropped.
    final needsMic =
        settings.audioMode == AudioMode.mic || settings.audioMode == AudioMode.both;
    if (needsMic && !await Permission.microphone.isGranted) {
      final status = await Permission.microphone.request();
      if (!mounted) return;
      if (!status.isGranted) {
        _toast('Microphone denied - recording will continue without mic audio.');
      }
    }

    final display = _display ?? await _bridge.displayMetrics();
    final ok = await _bridge.startRecording(
      settings: settings,
      display: display,
    );
    if (!mounted) return;
    if (!ok) {
      _toast('Screen capture permission was declined.');
    }
  }

  Future<void> _takeScreenshot() async {
    final display = _display ?? await _bridge.displayMetrics();
    final outcome = await _bridge.takeScreenshot(
      settings: widget.settings.value,
      display: display,
    );
    if (!mounted) return;
    switch (outcome) {
      case ScreenshotOutcome.saved:
        _toast('Screenshot saved to Pictures/RecorderZy.');
      case ScreenshotOutcome.needsAccessibility:
        _promptEnableScreenshotAccessibility();
      case ScreenshotOutcome.unsupported:
        _toast('Screenshots need Android 11 or newer.');
      case ScreenshotOutcome.failed:
        _toast('Screenshot failed. Please try again.');
    }
    await _refreshSystemFlags();
  }

  void _promptEnableScreenshotAccessibility() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text(
          'Screenshots use the RecorderZy accessibility service. '
          'Enable it once to allow real screenshots (no screen-record prompt).',
        ),
        duration: const Duration(seconds: 6),
        action: SnackBarAction(
          label: 'Enable',
          onPressed: () => _bridge.openAccessibilitySettings(),
        ),
      ),
    );
  }

  Future<void> _toggleOverlay() async {
    if (!_overlayPermission) {
      await _bridge.overlayRequestPermission();
      return;
    }
    await _bridge.showOverlay();
    await _bridge.setOverlayStyle(
      sizeDp: widget.settings.value.overlaySizeDp,
      alpha: widget.settings.value.overlayAlpha,
    );
    if (!mounted) return;
    _toast('Floating controls enabled.');
  }

  void _toast(String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text), duration: const Duration(seconds: 2)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final settings = widget.settings.value;
    return Scaffold(
      // SafeArea handles edge-to-edge insets that Android 16 mandates.
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _Header(state: _state),
              const SizedBox(height: 20),
              _RecordingButtons(
                state: _state,
                onStart: _startRecording,
                onPause: _bridge.pauseRecording,
                onResume: _bridge.resumeRecording,
                onStop: _bridge.stopRecording,
                onScreenshot: _takeScreenshot,
              ),
              const SizedBox(height: 20),

              SectionCard(
                title: 'Floating overlay',
                icon: Icons.bubble_chart_outlined,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      'Tap the handle to expand the 4-button drawer. Drag the '
                      'handle anywhere on screen. The overlay is hidden from '
                      'the recorded video automatically.',
                      // ignore: deprecated_member_use
                      style: TextStyle(color: cs.onSurface.withOpacity(0.7)),
                    ),
                    const SizedBox(height: 12),
                    AnimatedDrawerPreview(
                      primary: settings.primaryColor,
                      accent: settings.accentColor,
                      size: settings.overlaySizeDp.toDouble(),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton.icon(
                            onPressed: _toggleOverlay,
                            icon: Icon(_overlayPermission
                                ? Icons.layers_outlined
                                : Icons.lock_outline),
                            label: Text(_overlayPermission
                                ? 'Show floating controls'
                                : 'Grant overlay permission'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        OutlinedButton.icon(
                          onPressed: () => _bridge.hideOverlay(),
                          icon: const Icon(Icons.close),
                          label: const Text('Hide'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),

              SectionCard(
                title: 'System readiness',
                icon: Icons.shield_outlined,
                child: Column(
                  children: [
                    _statusRow(
                      'Microphone & notifications',
                      'Required for audio capture and the recording notification.',
                      onAction: _requestRuntimePermissions,
                      actionLabel: 'Request',
                    ),
                    _statusRow(
                      'Battery optimisation exemption',
                      _ignoresBattery
                          ? 'Granted - the OS will not throttle high-FPS recordings.'
                          : 'Disabled - tap to ask the OS to leave RecorderZy alone.',
                      onAction: _ignoresBattery
                          ? null
                          : () async {
                              await _bridge.requestIgnoreBatteryOptimizations();
                              await _refreshSystemFlags();
                            },
                      actionLabel: _ignoresBattery ? 'Granted' : 'Allow',
                    ),
                    _statusRow(
                      'Adaptive Refresh Rate (Android 16)',
                      _hasArr
                          ? 'Supported - suggested FPS: ${_suggestedFps ?? '-'}'
                          : 'Not available - using configured FPS.',
                    ),
                    _statusRow(
                      'Touch indicator service',
                      _accessibilityOn
                          ? 'Enabled - taps are visualised during recording.'
                          : 'Disabled - enable in Accessibility to use Show Touches.',
                      onAction: _accessibilityOn
                          ? null
                          : () async {
                              await _bridge.openAccessibilitySettings();
                              await _refreshSystemFlags();
                            },
                      actionLabel: _accessibilityOn ? 'Enabled' : 'Open settings',
                    ),
                  ],
                ),
              ),

              SizedBox(
                width: double.infinity,
                child: FilledButton.tonalIcon(
                  onPressed: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) =>
                            SettingsScreen(controller: widget.settings),
                      ),
                    );
                  },
                  icon: const Icon(Icons.tune),
                  label: const Text('Open full settings'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _statusRow(
    String title,
    String subtitle, {
    VoidCallback? onAction,
    String? actionLabel,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        // ignore: deprecated_member_use
                        color: Theme.of(context)
                            .colorScheme
                            .onSurface
                            // ignore: deprecated_member_use
                            .withOpacity(0.7),
                      ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          if (actionLabel != null)
            TextButton(
              onPressed: onAction,
              child: Text(actionLabel),
            ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.state});
  final RecorderState state;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      children: [
        Container(
          width: 56,
          height: 56,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            // ignore: deprecated_member_use
            color: cs.primary.withOpacity(0.14),
          ),
          child: Icon(Icons.fiber_manual_record, color: cs.secondary),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'RecorderZy',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
              ),
              Text(
                _phaseLabel(state),
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      // ignore: deprecated_member_use
                      color: cs.onSurface.withOpacity(0.7),
                    ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  String _phaseLabel(RecorderState s) {
    final t = _formatElapsed(s.elapsedMs);
    return switch (s.phase) {
      RecorderPhase.idle => 'Ready - HEVC + ADPF + ARR.',
      RecorderPhase.recording => 'Recording - $t',
      RecorderPhase.paused => 'Paused at $t',
    };
  }

  String _formatElapsed(int ms) {
    final s = ms ~/ 1000;
    final hh = s ~/ 3600;
    final mm = (s % 3600) ~/ 60;
    final ss = s % 60;
    return [hh, mm, ss]
        .map((v) => v.toString().padLeft(2, '0'))
        .join(':');
  }
}

class _RecordingButtons extends StatelessWidget {
  const _RecordingButtons({
    required this.state,
    required this.onStart,
    required this.onPause,
    required this.onResume,
    required this.onStop,
    required this.onScreenshot,
  });

  final RecorderState state;
  final VoidCallback onStart;
  final VoidCallback onPause;
  final VoidCallback onResume;
  final VoidCallback onStop;
  final VoidCallback onScreenshot;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final children = <Widget>[];
    switch (state.phase) {
      case RecorderPhase.idle:
        children.add(_bigButton(
          context,
          label: 'Start recording',
          icon: Icons.fiber_manual_record,
          color: cs.error,
          onTap: onStart,
        ));
        break;
      case RecorderPhase.recording:
        children.addAll([
          _bigButton(
            context,
            label: 'Pause',
            icon: Icons.pause_circle,
            color: cs.secondary,
            onTap: onPause,
          ),
          const SizedBox(width: 12),
          _bigButton(
            context,
            label: 'Stop',
            icon: Icons.stop_circle,
            color: cs.error,
            onTap: onStop,
          ),
        ]);
        break;
      case RecorderPhase.paused:
        children.addAll([
          _bigButton(
            context,
            label: 'Resume',
            icon: Icons.play_circle,
            color: cs.primary,
            onTap: onResume,
          ),
          const SizedBox(width: 12),
          _bigButton(
            context,
            label: 'Stop',
            icon: Icons.stop_circle,
            color: cs.error,
            onTap: onStop,
          ),
        ]);
        break;
    }
    children.addAll([
      const SizedBox(width: 12),
      _bigButton(
        context,
        label: 'Screenshot',
        icon: Icons.photo_camera_outlined,
        color: cs.primaryContainer,
        onTap: onScreenshot,
      ),
    ]);
    return Row(children: children);
  }

  Widget _bigButton(
    BuildContext context, {
    required String label,
    required IconData icon,
    required Color color,
    required VoidCallback onTap,
  }) {
    return Expanded(
      child: AspectRatio(
        aspectRatio: 1.6,
        child: InkWell(
          borderRadius: BorderRadius.circular(20),
          onTap: onTap,
          child: Ink(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(20),
              // ignore: deprecated_member_use
              color: color.withOpacity(0.18),
              border: Border.all(
                // ignore: deprecated_member_use
                color: color.withOpacity(0.45),
                width: 1.2,
              ),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, color: color, size: 30),
                const SizedBox(height: 8),
                Text(
                  label,
                  style: TextStyle(color: color, fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
