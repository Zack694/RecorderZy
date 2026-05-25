import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Holds user-customisable theme tokens (primary colour, background colour,
/// alpha, theme mode). Persisted to SharedPreferences and broadcast via
/// [ChangeNotifier] so any consumer rebuilds when colours change.
class ThemeController extends ChangeNotifier {
  ThemeController(this._prefs);

  static const _key = 'recorderzy.theme.v1';
  final SharedPreferences _prefs;

  ThemeMode _themeMode = ThemeMode.dark;
  Color _primary = const Color(0xFFFF1744);
  Color _background = const Color(0xFF0D0D12);
  double _alpha = 1.0;

  ThemeMode get themeMode => _themeMode;
  Color get primary => _primary.withValues(alpha: _alpha);
  Color get background => _background;
  double get alpha => _alpha;

  void load() {
    final raw = _prefs.getString(_key);
    if (raw == null) return;
    try {
      final json = jsonDecode(raw) as Map<String, Object?>;
      _themeMode = ThemeMode.values
          .firstWhere((m) => m.name == json['mode'], orElse: () => ThemeMode.dark);
      _primary = Color((json['primary'] as int?) ?? _primary.toARGB32());
      _background =
          Color((json['background'] as int?) ?? _background.toARGB32());
      _alpha = (json['alpha'] as num?)?.toDouble() ?? 1.0;
      notifyListeners();
    } catch (_) {
      // ignore corrupt theme blob
    }
  }

  void setThemeMode(ThemeMode m) {
    _themeMode = m;
    _save();
    notifyListeners();
  }

  void setPrimary(Color c) {
    _primary = c;
    _save();
    notifyListeners();
  }

  void setBackground(Color c) {
    _background = c;
    _save();
    notifyListeners();
  }

  void setAlpha(double a) {
    _alpha = a.clamp(0.2, 1.0);
    _save();
    notifyListeners();
  }

  Future<void> _save() async {
    await _prefs.setString(
      _key,
      jsonEncode({
        'mode': _themeMode.name,
        'primary': _primary.toARGB32(),
        'background': _background.toARGB32(),
        'alpha': _alpha,
      }),
    );
  }
}
