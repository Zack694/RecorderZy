import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'services/recorder_channel.dart';
import 'services/settings_service.dart';
import 'theme/theme_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Edge-to-edge: Android 16 mandates that we draw under system bars and
  // honour insets ourselves. We pair this with a transparent system-bar
  // colour so the Flutter UI's own background fully covers the device.
  await SystemChrome.setEnabledSystemUIMode(
    SystemUiMode.edgeToEdge,
  );
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Color(0x00000000),
      systemNavigationBarColor: Color(0x00000000),
      systemNavigationBarDividerColor: Color(0x00000000),
    ),
  );

  final prefs = await SharedPreferences.getInstance();
  final settings = SettingsService(prefs)..load();
  final theme = ThemeController(prefs)..load();
  final channel = RecorderChannel();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: settings),
        ChangeNotifierProvider.value(value: theme),
        Provider.value(value: channel),
      ],
      child: const RecorderZyApp(),
    ),
  );
}
