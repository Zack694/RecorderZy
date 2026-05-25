import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../settings/settings_model.dart';

/// Builds a Material 3 [ThemeData] from the user-customised colours. Cards
/// and other surfaces use the colour scheme directly so we don't have to
/// deal with the (frequently-renamed) `CardTheme`/`CardThemeData` flux.
ThemeData buildTheme(RecorderSettings settings) {
  final colorScheme = ColorScheme.fromSeed(
    seedColor: settings.primaryColor,
    brightness: Brightness.dark,
    primary: settings.primaryColor,
    secondary: settings.accentColor,
    surface: settings.backgroundColor,
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: colorScheme,
    scaffoldBackgroundColor: settings.backgroundColor,
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.transparent,
      elevation: 0,
      systemOverlayStyle: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        systemNavigationBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        systemNavigationBarIconBrightness: Brightness.light,
      ),
    ),
    sliderTheme: const SliderThemeData(
      showValueIndicator: ShowValueIndicator.always,
    ),
    snackBarTheme: SnackBarThemeData(
      // ignore: deprecated_member_use
      backgroundColor: Color.alphaBlend(
        // ignore: deprecated_member_use
        colorScheme.primary.withOpacity(0.18),
        settings.backgroundColor,
      ),
      contentTextStyle: TextStyle(color: colorScheme.onSurface),
      behavior: SnackBarBehavior.floating,
    ),
  );
}
