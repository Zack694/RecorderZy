import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/recorder_channel.dart';
import '../services/settings_service.dart';
import '../widgets/floating_drawer.dart';
import 'permissions_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool _isRecording = false;
  bool _isPaused = false;
  int _elapsedMs = 0;
  StreamSubscription<RecorderEvent>? _sub;
  Timer? _headroomTimer;
  double _thermal = 1.0;
  double? _cpu;
  double? _gpu;

  @override
  void initState() {
    super.initState();
    final ch = context.read<RecorderChannel>();
    _sub = ch.events.listen(_onEvent);
    _refreshState();
    _headroomTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => _refreshHeadroom(),
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    _headroomTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshState() async {
    final ch = context.read<RecorderChannel>();
    final running = await ch.isRecording();
    if (!mounted) return;
    setState(() => _isRecording = running);
  }

  Future<void> _refreshHeadroom() async {
    final ch = context.read<RecorderChannel>();
    final t = await ch.thermalHeadroom();
    final c = await ch.cpuHeadroom();
    final g = await ch.gpuHeadroom();
    if (!mounted) return;
    setState(() {
      _thermal = t;
      _cpu = c;
      _gpu = g;
    });
  }

  void _onEvent(RecorderEvent e) {
    switch (e.kind) {
      case 'started':
        setState(() {
          _isRecording = true;
          _isPaused = false;
          _elapsedMs = 0;
        });
        break;
      case 'stopped':
        setState(() {
          _isRecording = false;
          _isPaused = false;
          _elapsedMs = 0;
        });
        break;
      case 'paused':
        setState(() => _isPaused = true);
        break;
      case 'resumed':
        setState(() => _isPaused = false);
        break;
      case 'tick':
        setState(() {
          _elapsedMs = e.elapsedMs;
          _isPaused = e.paused;
        });
        break;
    }
  }

  Future<void> _toggleRecord() async {
    final ch = context.read<RecorderChannel>();
    final settings = context.read<SettingsService>().settings;
    if (_isRecording) {
      await ch.stopRecording();
    } else {
      // Ensure the floating bubble is visible while we record.
      if (await ch.canDrawOverlays()) {
        await ch.showFloatingBubble();
      }
      await ch.startRecording(settings);
    }
  }

  Future<void> _pauseResume() async {
    final ch = context.read<RecorderChannel>();
    if (!_isRecording) return;
    if (_isPaused) {
      await ch.resumeRecording();
    } else {
      await ch.pauseRecording();
    }
  }

  Future<void> _screenshot() async {
    final ch = context.read<RecorderChannel>();
    final settings = context.read<SettingsService>().settings;
    final uri = await ch.captureScreenshot(settings.screenshotScale);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(uri == null
            ? 'Screenshot failed (start a recording first or grant projection)'
            : 'Saved to gallery'),
      ),
    );
  }

  void _openSettings() {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const SettingsScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: Scaffold(
        appBar: AppBar(
          title: const Text('RecorderZy'),
          actions: [
            IconButton(
              icon: const Icon(Icons.shield_outlined),
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const PermissionsScreen()),
              ),
              tooltip: 'Permissions',
            ),
            IconButton(
              icon: const Icon(Icons.settings_outlined),
              onPressed: _openSettings,
              tooltip: 'Settings',
            ),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          children: [
            _statusCard(theme),
            const SizedBox(height: 16),
            _headroomCard(theme),
            const SizedBox(height: 24),
            Center(
              child: FloatingDrawer(
                isRecording: _isRecording,
                isPaused: _isPaused,
                onRecordToggle: _toggleRecord,
                onPauseResume: _pauseResume,
                onScreenshot: _screenshot,
                onSettings: _openSettings,
              ),
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: () async {
                final ch = context.read<RecorderChannel>();
                if (await ch.canDrawOverlays()) {
                  await ch.showFloatingBubble();
                  if (!mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Bubble shown')),
                  );
                } else {
                  await ch.openOverlaySettings();
                }
              },
              icon: const Icon(Icons.bubble_chart_outlined),
              label: const Text('Show floating bubble system-wide'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusCard(ThemeData theme) {
    final colors = theme.colorScheme;
    final mins = _elapsedMs ~/ 60_000;
    final secs = (_elapsedMs ~/ 1000) % 60;
    final timer = '${mins.toString().padLeft(2, '0')}:'
        '${secs.toString().padLeft(2, '0')}';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 6,
                  backgroundColor: _isRecording
                      ? (_isPaused ? Colors.amber : Colors.red)
                      : Colors.green,
                ),
                const SizedBox(width: 10),
                Text(
                  _isRecording
                      ? (_isPaused ? 'Paused' : 'Recording')
                      : 'Idle',
                  style: theme.textTheme.titleMedium,
                ),
                const Spacer(),
                Text(
                  timer,
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontFeatures: const [FontFeature.tabularFigures()],
                    color: colors.primary,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _headroomCard(ThemeData theme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('ADPF headroom',
                style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            _bar('Thermal', _thermal),
            if (_cpu != null) _bar('CPU', _cpu!),
            if (_gpu != null) _bar('GPU', _gpu!),
            if (_cpu == null && _gpu == null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  'CPU/GPU headroom unavailable on this device (requires '
                  'Android 16 / API 36).',
                  style: theme.textTheme.bodySmall,
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _bar(String label, double value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(width: 64, child: Text(label)),
          Expanded(
            child: LinearProgressIndicator(
              value: value.clamp(0.0, 1.0),
              minHeight: 8,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 48,
            child: Text(
              '${(value * 100).round()}%',
              textAlign: TextAlign.end,
            ),
          ),
        ],
      ),
    );
  }
}
