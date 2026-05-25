import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'screens/home_screen.dart';
import 'theme/app_theme.dart';
import 'theme/theme_controller.dart';

class RecorderZyApp extends StatelessWidget {
  const RecorderZyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<ThemeController>(
      builder: (context, controller, _) {
        return MaterialApp(
          title: 'RecorderZy',
          debugShowCheckedModeBanner: false,
          themeMode: controller.themeMode,
          theme: AppTheme.light(controller),
          darkTheme: AppTheme.dark(controller),
          home: const HomeScreen(),
        );
      },
    );
  }
}
