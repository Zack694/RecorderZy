import 'package:flutter/material.dart';

import 'core/settings/settings_controller.dart';
import 'core/theme/app_theme.dart';
import 'ui/home_screen.dart';

class RecorderZyApp extends StatelessWidget {
  const RecorderZyApp({super.key, required this.settings});

  final SettingsController settings;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: settings,
      builder: (_, __) {
        return MaterialApp(
          title: 'RecorderZy',
          debugShowCheckedModeBanner: false,
          theme: buildTheme(settings.value),
          // Single-screen home with internal navigation; keeps the surface
          // small and respects edge-to-edge insets cleanly.
          home: HomeScreen(settings: settings),
        );
      },
    );
  }
}
