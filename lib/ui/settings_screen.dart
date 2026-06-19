import 'package:flutter/material.dart';

import '../core/platform/recorder_bridge.dart';
import '../core/settings/settings_controller.dart';
import '../core/settings/settings_model.dart';
import 'widgets/color_picker_field.dart';
import 'widgets/section_card.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.controller});

  final SettingsController controller;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _bridge = RecorderBridge.instance;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final s = widget.controller.value;
        return Scaffold(
          appBar: AppBar(title: const Text('Settings')),
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              children: [
                _videoSection(s),
                _audioSection(s),
                _audioFxSection(s),
                _screenshotSection(s),
                _overlayStyleSection(s),
                _themeSection(s),
              ],
            ),
          ),
        );
      },
    );
  }

  // ---------------------------------------------------------------------------
  // Video
  // ---------------------------------------------------------------------------
  Widget _videoSection(RecorderSettings s) {
    return SectionCard(
      title: 'Video',
      icon: Icons.videocam_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          DropdownButtonFormField<QualityPreset>(
            // ignore: deprecated_member_use
            value: s.qualityPreset,
            decoration: const InputDecoration(labelText: 'Quality preset'),
            items: QualityPreset.values
                .map(
                  (q) => DropdownMenuItem(value: q, child: Text(q.label)),
                )
                .toList(),
            onChanged: (q) {
              if (q == null) return;
              widget.controller.update((p) => p.copyWith(
                    qualityPreset: q,
                    bitrateMbps: q.bitrateMbps,
                    frameRate: q.frameRate,
                  ));
            },
          ),
          const SizedBox(height: 12),
          _slider(
            label: 'Bitrate',
            value: s.bitrateMbps.toDouble(),
            min: 1,
            max: 60,
            divisions: 59,
            valueLabel: '${s.bitrateMbps} Mbps',
            onChanged: (v) => widget.controller
                .update((p) => p.copyWith(bitrateMbps: v.round())),
          ),
          _slider(
            label: 'Frame rate',
            value: s.frameRate.toDouble(),
            min: 24,
            max: 120,
            divisions: 96,
            valueLabel: '${s.frameRate} fps',
            onChanged: (v) => widget.controller
                .update((p) => p.copyWith(frameRate: v.round())),
          ),
          SwitchListTile.adaptive(
            value: s.useApv,
            title: const Text('Android 16 APV codec profile'),
            subtitle: const Text(
              'Force the Advanced Professional Video encoder when supported - '
              'lower CPU overhead, higher per-frame quality on capable hardware.',
            ),
            onChanged: (v) =>
                widget.controller.update((p) => p.copyWith(useApv: v)),
          ),
          OutlinedButton.icon(
            icon: const Icon(Icons.speed),
            label: const Text('Lock to ARR-suggested FPS'),
            onPressed: () async {
              final fallback = s.frameRate;
              final has = await _bridge.hasArrSupport();
              final suggested = await _bridge.getSuggestedFrameRate(fallback);
              if (!mounted) return;
              if (has) {
                widget.controller
                    .update((p) => p.copyWith(frameRate: suggested));
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text('Locked to $suggested fps.')),
                );
              } else {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Adaptive Refresh Rate not available on this device.'),
                  ),
                );
              }
            },
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Audio
  // ---------------------------------------------------------------------------
  Widget _audioSection(RecorderSettings s) {
    return SectionCard(
      title: 'Audio source',
      icon: Icons.mic_none_outlined,
      child: Column(
        children: AudioMode.values
            .map(
              (m) => RadioListTile<AudioMode>(
                value: m,
                // ignore: deprecated_member_use
                groupValue: s.audioMode,
                title: Text(m.label),
                // ignore: deprecated_member_use
                onChanged: (v) => widget.controller
                    .update((p) => p.copyWith(audioMode: v ?? AudioMode.mic)),
              ),
            )
            .toList(),
      ),
    );
  }

  Widget _audioFxSection(RecorderSettings s) {
    return SectionCard(
      title: 'Audio FX / DSP',
      icon: Icons.graphic_eq,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SwitchListTile.adaptive(
            value: s.noiseSuppression,
            title: const Text('High-quality noise suppression'),
            subtitle: const Text(
              'Engages the platform NoiseSuppressor effect to clean background '
              'hiss and HVAC rumble in real time on the mic stream.',
            ),
            onChanged: (v) => widget.controller
                .update((p) => p.copyWith(noiseSuppression: v)),
          ),
          DropdownButtonFormField<VoicePreset>(
            // ignore: deprecated_member_use
            value: s.voicePreset,
            decoration: const InputDecoration(labelText: 'Voice changer'),
            items: VoicePreset.values
                .map((p) => DropdownMenuItem(value: p, child: Text(p.label)))
                .toList(),
            onChanged: (p) => widget.controller
                .update((prev) => prev.copyWith(voicePreset: p ?? VoicePreset.normal)),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Screenshot
  // ---------------------------------------------------------------------------
  Widget _screenshotSection(RecorderSettings s) {
    return SectionCard(
      title: 'Screenshots',
      icon: Icons.photo_camera_outlined,
      child: DropdownButtonFormField<ScreenshotScale>(
        // ignore: deprecated_member_use
        value: s.screenshotScale,
        decoration: const InputDecoration(labelText: 'Resolution scale'),
        items: ScreenshotScale.values
            .map((v) => DropdownMenuItem(value: v, child: Text(v.label)))
            .toList(),
        onChanged: (v) => widget.controller
            .update((p) => p.copyWith(screenshotScale: v ?? ScreenshotScale.full)),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Overlay style
  // ---------------------------------------------------------------------------
  Widget _overlayStyleSection(RecorderSettings s) {
    return SectionCard(
      title: 'Floating overlay style',
      icon: Icons.bubble_chart_outlined,
      child: Column(
        children: [
          _slider(
            label: 'Size',
            value: s.overlaySizeDp.toDouble(),
            min: 40,
            max: 96,
            divisions: 56,
            valueLabel: '${s.overlaySizeDp} dp',
            onChanged: (v) {
              final size = v.round();
              widget.controller.update((p) => p.copyWith(overlaySizeDp: size));
              _bridge.setOverlayStyle(sizeDp: size, alpha: s.overlayAlpha);
            },
          ),
          _slider(
            label: 'Opacity',
            value: s.overlayAlpha,
            min: 0.2,
            max: 1.0,
            divisions: 16,
            valueLabel: '${(s.overlayAlpha * 100).round()}%',
            onChanged: (v) {
              widget.controller
                  .update((p) => p.copyWith(overlayAlpha: v));
              _bridge.setOverlayStyle(sizeDp: s.overlaySizeDp, alpha: v);
            },
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Theme
  // ---------------------------------------------------------------------------
  Widget _themeSection(RecorderSettings s) {
    return SectionCard(
      title: 'Theme',
      icon: Icons.palette_outlined,
      child: Column(
        children: [
          ColorPickerField(
            label: 'Primary colour',
            color: s.primaryColor,
            onColorChanged: (c) =>
                widget.controller.update((p) => p.copyWith(primaryColor: c)),
          ),
          ColorPickerField(
            label: 'Accent colour',
            color: s.accentColor,
            onColorChanged: (c) =>
                widget.controller.update((p) => p.copyWith(accentColor: c)),
          ),
          ColorPickerField(
            label: 'Background colour',
            color: s.backgroundColor,
            onColorChanged: (c) =>
                widget.controller.update((p) => p.copyWith(backgroundColor: c)),
          ),
          _slider(
            label: 'Surface alpha',
            value: s.surfaceAlpha,
            min: 0.4,
            max: 1.0,
            divisions: 12,
            valueLabel: '${(s.surfaceAlpha * 100).round()}%',
            onChanged: (v) => widget.controller
                .update((p) => p.copyWith(surfaceAlpha: v)),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  Widget _slider({
    required String label,
    required double value,
    required double min,
    required double max,
    required int divisions,
    required String valueLabel,
    required ValueChanged<double> onChanged,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(label)),
              Text(valueLabel),
            ],
          ),
          Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            divisions: divisions,
            label: valueLabel,
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }
}
