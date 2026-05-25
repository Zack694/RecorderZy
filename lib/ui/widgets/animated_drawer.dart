import 'package:flutter/material.dart';

/// In-app rendition of the on-screen floating drawer. Used in the home screen
/// preview tile so the user can see exactly how the overlay's animation will
/// feel before they enable it. It runs at the device's vsync (up to 120 Hz on
/// Android 16 ARR-capable panels) thanks to Flutter's AnimationController +
/// Ticker pacing.
class AnimatedDrawerPreview extends StatefulWidget {
  const AnimatedDrawerPreview({
    super.key,
    required this.primary,
    required this.accent,
    this.size = 56,
  });

  final Color primary;
  final Color accent;
  final double size;

  @override
  State<AnimatedDrawerPreview> createState() => _AnimatedDrawerPreviewState();
}

class _AnimatedDrawerPreviewState extends State<AnimatedDrawerPreview>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 240),
    reverseDuration: const Duration(milliseconds: 180),
  );
  late final CurvedAnimation _curve =
      CurvedAnimation(parent: _ctrl, curve: Curves.easeOutBack);

  bool _expanded = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _toggle() {
    setState(() => _expanded = !_expanded);
    if (_expanded) {
      _ctrl.forward();
    } else {
      _ctrl.reverse();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.size;
    return SizedBox(
      height: s + 24,
      child: Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.centerLeft,
        children: [
          // Drawer
          AnimatedBuilder(
            animation: _curve,
            builder: (_, __) {
              return Positioned(
                left: s + 8,
                top: (s - 56) / 2 + 12,
                child: Transform.scale(
                  alignment: Alignment.centerLeft,
                  scale: 0.4 + 0.6 * _curve.value,
                  child: Opacity(
                    opacity: _curve.value.clamp(0.0, 1.0),
                    child: Container(
                      height: 56,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      decoration: BoxDecoration(
                        color: widget.primary,
                        borderRadius: BorderRadius.circular(28),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _drawerIcon(Icons.fiber_manual_record, widget.accent, _toggle),
                          _drawerIcon(Icons.photo_camera_outlined, Colors.white, _toggle),
                          _drawerIcon(Icons.tune, Colors.white, _toggle),
                          _drawerIcon(Icons.close, Colors.white, _toggle),
                        ],
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
          // Handle
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: GestureDetector(
              onTap: _toggle,
              child: Container(
                width: s,
                height: s,
                decoration: BoxDecoration(
                  color: widget.primary,
                  shape: BoxShape.circle,
                  border: Border.all(
                    // ignore: deprecated_member_use
                    color: Colors.white.withOpacity(0.18),
                    width: 1.2,
                  ),
                ),
                child: Icon(
                  Icons.fiber_manual_record,
                  color: widget.accent,
                  size: s * 0.42,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _drawerIcon(IconData icon, Color color, VoidCallback onTap) {
    return InkWell(
      borderRadius: BorderRadius.circular(20),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
        child: Icon(icon, color: color, size: 20),
      ),
    );
  }
}
