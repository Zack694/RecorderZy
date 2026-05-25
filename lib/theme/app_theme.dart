import 'package:flutter/material.dart';

import 'theme_controller.dart';

class AppTheme {
  static ThemeData light(ThemeController c) {
    final scheme = ColorScheme.fromSeed(
      seedColor: c.primary,
      brightness: Brightness.light,
      primary: c.primary,
      surface: c.background,
    );
    return _build(scheme, c);
  }

  static ThemeData dark(ThemeController c) {
    final scheme = ColorScheme.fromSeed(
      seedColor: c.primary,
      brightness: Brightness.dark,
      primary: c.primary,
      surface: c.background,
    );
    return _build(scheme, c);
  }

  static ThemeData _build(ColorScheme scheme, ThemeController c) {
    return ThemeData(
      colorScheme: scheme,
      useMaterial3: true,
      scaffoldBackgroundColor: scheme.surface,
      visualDensity: VisualDensity.compact,
      cardTheme: CardThemeData(
        color: scheme.surfaceContainerHigh,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStatePropertyAll(scheme.primary),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected)
              ? scheme.primary.withValues(alpha: 0.5)
              : scheme.outlineVariant,
        ),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: scheme.primary,
        thumbColor: scheme.primary,
        overlayColor: scheme.primary.withValues(alpha: 0.16),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        scrolledUnderElevation: 0,
        elevation: 0,
        centerTitle: false,
      ),
    );
  }
}
