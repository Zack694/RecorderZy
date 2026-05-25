import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/recorder_channel.dart';

class PermissionsScreen extends StatefulWidget {
  const PermissionsScreen({super.key});

  @override
  State<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends State<PermissionsScreen>
    with WidgetsBindingObserver {
  bool _overlay = false;
  bool _battery = false;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-poll permission state every time the user comes back from a system
    // settings page — the platform doesn't push notifications for grants.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final ch = context.read<RecorderChannel>();
    final ov = await ch.canDrawOverlays();
    final bo = await ch.isIgnoringBatteryOptimizations();
    if (!mounted) return;
    setState(() {
      _overlay = ov;
      _battery = bo;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final ch = context.read<RecorderChannel>();
    return SafeArea(
      child: Scaffold(
        appBar: AppBar(title: const Text('Permissions & setup')),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _tile(
                    icon: Icons.layers_outlined,
                    title: 'Display over other apps',
                    subtitle:
                        'Required for the floating bubble. The overlay window uses FLAG_SECURE so it never appears in the saved video.',
                    granted: _overlay,
                    onTap: () => ch.openOverlaySettings(),
                  ),
                  _tile(
                    icon: Icons.battery_alert_outlined,
                    title: 'Disable battery optimisation',
                    subtitle:
                        'Stops the OS throttling the encoder while you record at 120 FPS.',
                    granted: _battery,
                    onTap: () => ch.requestIgnoreBatteryOptimizations(),
                  ),
                  _tile(
                    icon: Icons.notifications_active_outlined,
                    title: 'Notifications enabled',
                    subtitle:
                        'Required to display the recording status and quick-actions (Pause / Resume / Stop).',
                    granted: true,
                    onTap: () => ch.openAppNotificationSettings(),
                  ),
                  const Padding(
                    padding: EdgeInsets.fromLTRB(8, 24, 8, 8),
                    child: Text(
                      'Microphone, screen-capture and storage permissions are '
                      'requested at the start of each recording session – '
                      'Android 14+ requires fresh tokens every time.',
                      style: TextStyle(fontSize: 12),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _tile({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool granted,
    required VoidCallback onTap,
  }) {
    return Card(
      child: ListTile(
        leading: Icon(icon),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: granted
            ? const Icon(Icons.check_circle, color: Colors.green)
            : const Icon(Icons.warning_amber_rounded, color: Colors.amber),
        onTap: onTap,
      ),
    );
  }
}
