import 'dart:math' as math;

import 'package:flutter/material.dart';

/// In-app preview / driver for the four-button radial drawer.
///
/// The same UI is rendered by the native [FloatingOverlayService] when the app
/// is backgrounded — this Dart version is what users see when the app is in
/// the foreground (the bubble is auto-hidden in that mode for a cleaner feel).
///
/// The expansion animation is driven by an [AnimationController] running at the
/// device's native refresh rate so it remains smooth on 90 / 120 Hz panels.
class FloatingDrawer extends StatefulWidget {
  const FloatingDrawer({
    super.key,
    required this.isRecording,
    required this.isPaused,
    required this.onRecordToggle,
    required this.onPauseResume,
    required this.onScreenshot,
    required this.onSettings,
  });

  final bool isRecording;
  final bool isPaused;
  final VoidCallback onRecordToggle;
  final VoidCallback onPauseResume;
  final VoidCallback onScreenshot;
  final VoidCallback onSettings;

  @override
  State<FloatingDrawer> createState() => _FloatingDrawerState();
}

class _FloatingDrawerState extends State<FloatingDrawer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  bool _expanded = false;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 220),
      reverseDuration: const Duration(milliseconds: 160),
    );
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _toggle() {
    setState(() {
      _expanded = !_expanded;
      if (_expanded) {
        _ctrl.forward();
      } else {
        _ctrl.reverse();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;
    return SizedBox(
      width: 220,
      height: 220,
      child: Stack(
        alignment: Alignment.center,
        children: [
          // Sub-buttons fan out from the centre.
          ..._buildSubButtons(),
          // Main bubble.
          GestureDetector(
            onTap: _toggle,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: 88,
              height: 88,
              decoration: BoxDecoration(
                color: primary.withValues(alpha: 0.95),
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                    color: primary.withValues(alpha: 0.4),
                    blurRadius: 24,
                    spreadRadius: 2,
                  ),
                ],
              ),
              child: Center(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 200),
                  child: Icon(
                    _mainIcon(),
                    key: ValueKey(_mainIcon()),
                    color: Colors.white,
                    size: 36,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  IconData _mainIcon() {
    if (!widget.isRecording) return Icons.fiber_manual_record;
    if (widget.isPaused) return Icons.play_arrow;
    return Icons.pause;
  }

  List<Widget> _buildSubButtons() {
    final items = <_SubBtn>[
      _SubBtn(
        icon: widget.isRecording
            ? Icons.stop
            : Icons.fiber_manual_record,
        label: widget.isRecording ? 'Stop' : 'Record',
        angle: -120, // top-left
        onTap: widget.onRecordToggle,
        accent: Colors.redAccent,
      ),
      _SubBtn(
        icon: Icons.camera_alt,
        label: 'Shot',
        angle: -60, // top-right
        onTap: widget.onScreenshot,
        accent: Colors.blueAccent,
      ),
      _SubBtn(
        icon: Icons.settings,
        label: 'Settings',
        angle: 60,
        onTap: widget.onSettings,
        accent: Colors.amberAccent,
      ),
      _SubBtn(
        icon: Icons.close,
        label: 'Close',
        angle: 120,
        onTap: _toggle,
        accent: Colors.white24,
      ),
    ];

    return items.map((item) {
      final radians = item.angle * math.pi / 180.0;
      const radius = 80.0;
      return AnimatedBuilder(
        animation: _ctrl,
        builder: (ctx, _) {
          final t = Curves.easeOutBack.transform(_ctrl.value);
          final dx = (radius * t) * math.cos(radians);
          final dy = (radius * t) * math.sin(radians);
          return Transform.translate(
            offset: Offset(dx, dy),
            child: Opacity(
              opacity: _ctrl.value.clamp(0.0, 1.0),
              child: GestureDetector(
                onTap: item.onTap,
                child: Container(
                  width: 56,
                  height: 56,
                  decoration: BoxDecoration(
                    color: item.accent.withValues(alpha: 0.85),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(item.icon, color: Colors.white, size: 24),
                ),
              ),
            ),
          );
        },
      );
    }).toList();
  }
}

class _SubBtn {
  _SubBtn({
    required this.icon,
    required this.label,
    required this.angle,
    required this.onTap,
    required this.accent,
  });
  final IconData icon;
  final String label;
  final double angle;
  final VoidCallback onTap;
  final Color accent;
}
