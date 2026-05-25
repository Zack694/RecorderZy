import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/recording_settings.dart';
import '../services/recorder_channel.dart';
import '../services/settings_service.dart';
import '../theme/theme_controller.dart';
import '../widgets/color_picker_tile.dart';
import '../widgets/section_card.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _arrSupported = false;
  double _suggestedFps = 60;

  @override
  void initState() {
    super.initState();
    _probeArr();
  }

  Future<void> _probeArr() async {
    final ch = context.read<RecorderChannel>();
    final supported = await ch.hasArrSupport();
    final suggested = await ch.suggestedFrameRate(3 /* HIGH */);
    if (!mounted) return;
    setState(() {
      _arrSupported = supported;
      _suggestedFps = suggested;
    });
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final theme = context.watch<ThemeController>();
    final s = settings.settings;

    return SafeArea(
      child: Scaffold(
        appBar: AppBar(title: const Text('Settings')),
        body: ListView(
          children: [
            // -------- Video --------
            SectionCard(
              icon: Icons.movie_filter_outlined,
              title: 'Video',
              children: [
                _qualityDropdown(settings, s),
                const SizedBox(height: 8),
                _bitrateSlider(settings, s),
                const SizedBox(height: 8),
                _frameRateRow(settings, s),
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('APV codec (Android 16+)'),
                  subtitle: const Text(
                    'Force APV (Advanced Professional Video) instead of HEVC. '
                    'Lower CPU at higher bitrate cost.',
                  ),
                  value: s.useApvCodec,
                  onChanged: settings.setUseApv,
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Match panel ARR'),
                  subtitle: Text(
                    _arrSupported
                        ? 'Sync FPS with the panel\'s adaptive refresh rate '
                            '(suggested: ${_suggestedFps.toStringAsFixed(0)} Hz)'
                        : 'Adaptive Refresh Rate not supported on this device',
                  ),
                  value: s.matchPanelArr,
                  onChanged: _arrSupported ? settings.setMatchPanelArr : null,
                ),
              ],
            ),

            // -------- Audio --------
            SectionCard(
              icon: Icons.graphic_eq,
              title: 'Audio',
              children: [
                Column(
                  children: AudioMode.values
                      .map((mode) => RadioListTile<AudioMode>(
                            contentPadding: EdgeInsets.zero,
                            title: Text(mode.label),
                            value: mode,
                            groupValue: s.audioMode,
                            onChanged: (v) {
                              if (v != null) settings.setAudioMode(v);
                            },
                          ))
                      .toList(),
                ),
                const Divider(),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('AI noise suppression'),
                  subtitle: const Text(
                    'Hardware NoiseSuppressor effect (RNNoise / WebRTC NS '
                    'fallback). Applied to the microphone stream only.',
                  ),
                  value: s.noiseSuppression,
                  onChanged: settings.setNoiseSuppression,
                ),
                DropdownButtonFormField<VoicePreset>(
                  initialValue: s.voicePreset,
                  decoration: const InputDecoration(
                    labelText: 'Voice changer',
                    border: OutlineInputBorder(),
                  ),
                  items: VoicePreset.values
                      .map((p) => DropdownMenuItem(
                            value: p,
                            child: Text(p.label),
                          ))
                      .toList(),
                  onChanged: (v) {
                    if (v != null) settings.setVoicePreset(v);
                  },
                ),
              ],
            ),

            // -------- Utility --------
            SectionCard(
              icon: Icons.touch_app_outlined,
              title: 'Utility',
              children: [
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Show touches'),
                  subtitle: const Text(
                    'Render a fading dot at every tap. Visible on screen but '
                    'excluded from the saved video via FLAG_SECURE.',
                  ),
                  value: s.showTouches,
                  onChanged: (v) {
                    settings.setShowTouches(v);
                    final ch = context.read<RecorderChannel>();
                    if (v) {
                      ch.showTouchIndicator();
                    } else {
                      ch.hideTouchIndicator();
                    }
                  },
                ),
                Row(
                  children: [
                    const Expanded(child: Text('Bubble size')),
                    Expanded(
                      flex: 3,
                      child: Slider(
                        min: 40,
                        max: 120,
                        divisions: 16,
                        value: s.bubbleSizeDp.toDouble(),
                        label: '${s.bubbleSizeDp} dp',
                        onChanged: (v) {
                          settings.setBubbleSize(v.round());
                          context.read<RecorderChannel>().configureBubble(
                                sizeDp: v.round(),
                                alpha: s.bubbleAlpha,
                              );
                        },
                      ),
                    ),
                  ],
                ),
                Row(
                  children: [
                    const Expanded(child: Text('Bubble opacity')),
                    Expanded(
                      flex: 3,
                      child: Slider(
                        min: 0.2,
                        max: 1.0,
                        divisions: 16,
                        value: s.bubbleAlpha,
                        label: '${(s.bubbleAlpha * 100).round()}%',
                        onChanged: (v) {
                          settings.setBubbleAlpha(v);
                          context.read<RecorderChannel>().configureBubble(
                                sizeDp: s.bubbleSizeDp,
                                alpha: v,
                              );
                        },
                      ),
                    ),
                  ],
                ),
              ],
            ),

            // -------- Screenshot --------
            SectionCard(
              icon: Icons.camera_alt_outlined,
              title: 'Screenshot',
              children: [
                Row(
                  children: [
                    const Expanded(child: Text('Resolution')),
                    Expanded(
                      flex: 3,
                      child: Slider(
                        min: 0.25,
                        max: 1.0,
                        divisions: 6,
                        value: s.screenshotScale,
                        label: '${(s.screenshotScale * 100).round()}%',
                        onChanged: settings.setScreenshotScale,
                      ),
                    ),
                  ],
                ),
              ],
            ),

            // -------- Theme --------
            SectionCard(
              icon: Icons.palette_outlined,
              title: 'Theme',
              children: [
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Theme mode'),
                  trailing: SegmentedButton<ThemeMode>(
                    segments: const [
                      ButtonSegment(
                        value: ThemeMode.system,
                        icon: Icon(Icons.brightness_auto),
                      ),
                      ButtonSegment(
                        value: ThemeMode.light,
                        icon: Icon(Icons.light_mode),
                      ),
                      ButtonSegment(
                        value: ThemeMode.dark,
                        icon: Icon(Icons.dark_mode),
                      ),
                    ],
                    selected: {theme.themeMode},
                    onSelectionChanged: (s) =>
                        theme.setThemeMode(s.first),
                  ),
                ),
                ColorPickerTile(
                  label: 'Primary',
                  color: theme.primary,
                  onChanged: theme.setPrimary,
                ),
                ColorPickerTile(
                  label: 'Background',
                  color: theme.background,
                  onChanged: theme.setBackground,
                ),
                Row(
                  children: [
                    const Expanded(child: Text('Alpha')),
                    Expanded(
                      flex: 3,
                      child: Slider(
                        min: 0.2,
                        max: 1.0,
                        divisions: 16,
                        value: theme.alpha,
                        label: '${(theme.alpha * 100).round()}%',
                        onChanged: theme.setAlpha,
                      ),
                    ),
                  ],
                ),
              ],
            ),

            // -------- Crash recovery --------
            SectionCard(
              icon: Icons.shield_outlined,
              title: 'Crash recovery',
              children: [
                Row(
                  children: [
                    const Expanded(child: Text('Metadata flush')),
                    Expanded(
                      flex: 3,
                      child: Slider(
                        min: 1,
                        max: 10,
                        divisions: 9,
                        value: s.muxerFlushSeconds.toDouble(),
                        label: '${s.muxerFlushSeconds}s',
                        onChanged: (v) =>
                            settings.setMuxerFlush(v.round()),
                      ),
                    ),
                  ],
                ),
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    'How often the muxer fsyncs the MP4 to disk. Lower = '
                    'tighter crash-window guarantees, slightly higher I/O cost.',
                    style: TextStyle(fontSize: 12),
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Widget _qualityDropdown(SettingsService settings, RecordingSettings s) {
    return DropdownButtonFormField<QualityPreset>(
      initialValue: s.qualityPreset,
      decoration: const InputDecoration(
        labelText: 'Quality preset',
        border: OutlineInputBorder(),
      ),
      items: QualityPreset.values
          .map((p) => DropdownMenuItem(
                value: p,
                child: Text(p.label),
              ))
          .toList(),
      onChanged: (v) {
        if (v != null) settings.setQualityPreset(v);
      },
    );
  }

  Widget _bitrateSlider(SettingsService settings, RecordingSettings s) {
    return Row(
      children: [
        const Expanded(child: Text('Bitrate')),
        Expanded(
          flex: 3,
          child: Slider(
            min: 2_000_000,
            max: 80_000_000,
            divisions: 39,
            value: s.bitrateBps.toDouble(),
            label: '${(s.bitrateBps / 1_000_000).toStringAsFixed(0)} Mbps',
            onChanged: (v) {
              settings.setBitrate(v.round());
              if (s.qualityPreset != QualityPreset.custom) {
                settings.setQualityPreset(QualityPreset.custom);
              }
            },
          ),
        ),
      ],
    );
  }

  Widget _frameRateRow(SettingsService settings, RecordingSettings s) {
    final options = <int>[24, 30, 60, 90, 120];
    return Wrap(
      spacing: 8,
      children: options.map((fps) {
        final selected = s.frameRate == fps;
        return ChoiceChip(
          label: Text('$fps fps'),
          selected: selected,
          onSelected: (_) => settings.setFrameRate(fps),
        );
      }).toList(),
    );
  }
}
