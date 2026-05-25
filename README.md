# RecorderZy

Ultra-lightweight Android **screen recording + screenshot** application
targeted at Android 16 (API 36). Flutter for the UI, Native Kotlin for every
low-level system service (MediaProjection, MediaRecorder, MediaCodec,
AudioRecord, VirtualDisplay, MediaStore, ADPF, ARR, foreground notifications),
glued together with Method Channels.

[![Android CI](https://github.com/Zack694/RecorderZy/actions/workflows/android.yml/badge.svg)](https://github.com/Zack694/RecorderZy/actions/workflows/android.yml)

---

## Highlights

| Area | What it does |
|---|---|
| **Encoding** | Hardware HEVC by default. APV (Advanced Professional Video, Android 16) one-tap toggle. |
| **Performance** | ADPF `getCpuHeadroom` / `getGpuHeadroom` / `PerformanceHintManager` integration. ARR sync via `Display.hasArrSupport` + `getSuggestedFrameRate`. |
| **Recording UX** | Persistent draggable floating bubble + live timer + radial 4-button drawer, all rendered with `FLAG_SECURE` so they are **invisible to MediaProjection capture**. |
| **Pause / resume** | Single output file – no chunking, no splits. MediaRecorder `pause()` / `resume()` on the HEVC path; manual PTS-subtraction gating on the APV codec path. |
| **Audio** | Mute / Mic / Internal / Both. Mic stream gets hardware NoiseSuppressor + DSP voice changer (Normal / Deep / Robotic / Helium / Radio). |
| **Storage** | All output written to the public `RecorderZy` album via the MediaStore API. Zero `WRITE_EXTERNAL_STORAGE` requirement. |
| **Crash safety** | Periodic `fsync()` heartbeat + Application-level shutdown hook so a partial MP4 is recoverable, not corrupted. |
| **System hygiene** | Foreground notification (`mediaProjection|microphone`) with quick actions (Pause / Resume / Stop). Dynamic orientation listener resizes the VirtualDisplay live. |

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                          FLUTTER (Dart UI)                         │
│                                                                    │
│  HomeScreen ─── SettingsScreen ─── PermissionsScreen               │
│       │             │                                              │
│       └─ FloatingDrawer (radial 4-btn animated, in-app preview)    │
│                                                                    │
│  SettingsService (SharedPreferences) ─── ThemeController           │
│  RecorderChannel (MethodChannel wrapper, broadcast event stream)   │
└────────┬───────────────────────────────────────────────────────────┘
         │  recorderzy/recorder · /overlay · /permissions · /performance
┌────────▼───────────────────────────────────────────────────────────┐
│                          NATIVE KOTLIN                             │
│                                                                    │
│  MainActivity (FlutterActivity, edge-to-edge enabled)              │
│  MediaProjectionRequestActivity (transparent, fresh-token shim)    │
│  MethodChannels (router)                                           │
│                                                                    │
│  ┌────────────────── service/ ──────────────────┐                  │
│  │ ScreenRecordService  (foreground service,    │                  │
│  │   FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION   │                  │
│  │   | MICROPHONE, Pause/Resume/Stop quick acts │                  │
│  │   1 Hz timer pump → Flutter + bubble)        │                  │
│  │ FloatingOverlayService (TYPE_APPLICATION_    │                  │
│  │   OVERLAY + FLAG_SECURE, fully draggable,    │                  │
│  │   live timer, 4-button native drawer)        │                  │
│  │ TouchIndicatorService  (FLAG_SECURE dot)     │                  │
│  └──────────────────────────────────────────────┘                  │
│                                                                    │
│  ┌────────────────── recorder/ ─────────────────┐                  │
│  │ ScreenRecorder  (HEVC via MediaRecorder /    │                  │
│  │   APV via MediaCodec+MediaMuxer, single-file │                  │
│  │   pause/resume, orientation listener,        │                  │
│  │   muxer flush heartbeat)                     │                  │
│  │ ScreenshotCapture (ImageReader-backed)       │                  │
│  │ RecordingSession  (process-wide state)       │                  │
│  └──────────────────────────────────────────────┘                  │
│                                                                    │
│  ┌────── audio/ ──────┐  ┌──────── perf/ ───────┐                  │
│  │ AudioCaptureMixer  │  │ AdpfMonitor          │                  │
│  │ VoiceChanger       │  │   (PerformanceHint   │                  │
│  │ NoiseSuppressorFx  │  │    Manager,          │                  │
│  └────────────────────┘  │    getThermalHead-   │                  │
│                          │    room, reflective  │                  │
│  ┌── storage/ ────────┐  │    cpu/gpu headroom) │                  │
│  │ MediaStoreHelper   │  │ ArrManager           │                  │
│  │  (RecorderZy album)│  │   (hasArrSupport,    │                  │
│  └────────────────────┘  │    getSuggestedFps)  │                  │
│                          └──────────────────────┘                  │
└────────────────────────────────────────────────────────────────────┘
```

---

## Project layout

```
RecorderZy/
├── pubspec.yaml                Flutter project metadata
├── analysis_options.yaml       Lints
├── lib/
│   ├── main.dart               Entry – edge-to-edge SystemUiMode + DI
│   ├── app.dart                MaterialApp + theming
│   ├── models/
│   │   └── recording_settings.dart
│   ├── services/
│   │   ├── recorder_channel.dart   4-channel MethodChannel wrapper
│   │   └── settings_service.dart
│   ├── theme/
│   │   ├── theme_controller.dart   Primary / background / alpha pickers
│   │   └── app_theme.dart
│   ├── widgets/
│   │   ├── floating_drawer.dart    In-app preview of the 4-btn drawer
│   │   ├── color_picker_tile.dart
│   │   └── section_card.dart
│   └── screens/
│       ├── home_screen.dart        Status, ADPF headroom, drawer, bubble launch
│       ├── settings_screen.dart    All persistent prefs
│       └── permissions_screen.dart Overlay / battery / notifications setup
└── android/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts        compileSdk 36, minSdk 26
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml Permissions + 3 services
            ├── res/                Vectors, layouts, themes
            └── kotlin/com/recorderzy/app/
                ├── RecorderApp.kt           Application + shutdown hook
                ├── MainActivity.kt          FlutterActivity, edge-to-edge
                ├── MediaProjectionRequestActivity.kt
                ├── channels/MethodChannels.kt
                ├── service/
                │   ├── ScreenRecordService.kt
                │   ├── FloatingOverlayService.kt
                │   └── TouchIndicatorService.kt
                ├── recorder/
                │   ├── ScreenRecorder.kt    HEVC + APV pipelines
                │   ├── ScreenshotCapture.kt
                │   └── RecordingSession.kt
                ├── audio/
                │   ├── AudioCaptureMixer.kt
                │   ├── VoiceChanger.kt
                │   └── NoiseSuppressorEffect.kt
                ├── perf/
                │   ├── AdpfMonitor.kt
                │   └── ArrManager.kt
                ├── storage/
                │   └── MediaStoreHelper.kt
                └── notification/
                    └── RecorderNotifications.kt
```

---

## Building

### CI – GitHub Actions

Push to any `main`, `master`, `feat/**` or `fix/**` branch and the
[`Android CI`](.github/workflows/android.yml) workflow will:

1. Install JDK 17 + Android SDK platform 36 + build-tools 36.0.0.
2. Install Flutter 3.27.4 stable.
3. Run `flutter analyze`.
4. Run `flutter build apk --release --split-per-abi` to produce per-ABI
   APKs (arm, arm64, x64).
5. Upload the resulting APKs as the `recorderzy-apks` artifact.

### Local

Requirements:
- Flutter `>= 3.27`
- Android SDK platform 36 + build-tools 36.0.0
- JDK 17

```bash
flutter pub get
flutter build apk --release
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

---

## Permissions / first-run setup

Open **Settings → Permissions & setup** inside the app and grant:

| Permission | Why |
|---|---|
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Floating bubble + touch indicator overlay. |
| Disable battery optimisation | Keeps the encoder un-throttled at 120 FPS. |
| Notifications (`POST_NOTIFICATIONS`, Android 13+) | Persistent Pause / Resume / Stop quick actions. |

`MEDIA_PROJECTION` and `RECORD_AUDIO` are always requested *fresh* at the
start of every recording session (Android 14+ revokes cached tokens).

Optional: `WRITE_SECURE_SETTINGS` granted via `adb shell pm grant
com.recorderzy.app android.permission.WRITE_SECURE_SETTINGS` enables the
system-wide *Show Touches* fallback for users who do not want to enable an
Accessibility Service.

---

## Spec compliance map

| Spec point | Implementation |
|---|---|
| Flutter UI / themes / drawer animations | `lib/screens/*`, `lib/widgets/floating_drawer.dart`, `lib/theme/*` |
| Edge-to-edge (`SafeArea` everywhere, transparent system bars) | `lib/main.dart` (`SystemUiMode.edgeToEdge`), every screen wraps `Scaffold` in `SafeArea` |
| Native Kotlin via MethodChannels | `MethodChannels.kt` (4 channels) |
| Hardware HEVC default | `ScreenRecorder.startMediaRecorderPath` (`MediaRecorder.VideoEncoder.HEVC`) |
| APV codec toggle | `ScreenRecorder.startMediaCodecPath` (`MediaFormat.MIMETYPE_VIDEO_APV` literal) |
| ADPF headroom | `AdpfMonitor.thermalHeadroom`, `cpuHeadroom`, `gpuHeadroom`, `beginSession` |
| Floating bubble (size / opacity / drag / timer) | `FloatingOverlayService` |
| Bubble + touch indicator excluded from capture | `WindowManager.LayoutParams.FLAG_SECURE` on every overlay window |
| Pause / Resume single-file | `MediaRecorder.pause()/resume()` (HEVC) + manual PTS gating (APV) |
| 4-button radial drawer (Record / Shot / Settings / Close) | `lib/widgets/floating_drawer.dart` (in-app) + native fallback in `FloatingOverlayService.toggleDrawer` |
| Bitrate / FPS / quality settings | `lib/screens/settings_screen.dart` |
| ARR matching (`hasArrSupport`, `getSuggestedFrameRate`) | `ArrManager.kt`, surfaced as the *Match panel ARR* toggle |
| Audio Mute / Mic / Internal / Both | `AudioCaptureMixer.kt` |
| Noise suppression | `NoiseSuppressorEffect.kt` (hardware) |
| Voice changer presets | `VoiceChanger.kt` (5 presets, in-place 16-bit PCM DSP) |
| Show touches | `TouchIndicatorService.kt` (`FLAG_SECURE` overlay) |
| Screenshot scale (100 / 75 / 50 / …) | Slider in settings → `ScreenshotCapture.queueOneShot(scale)` |
| Theme / colour picker / alpha | `ThemeController` + `ColorPickerTile` |
| Fresh `createScreenCaptureIntent` per session | `MainActivity.launchProjectionConsent` + `MediaProjectionRequestActivity` |
| Foreground notification with quick actions | `RecorderNotifications.kt` + `ScreenRecordService` action handlers |
| Dynamic orientation handling | `ScreenRecorder.startOrientationListener` resizes VirtualDisplay live |
| MediaStore + RecorderZy album | `MediaStoreHelper.kt` (`Movies/RecorderZy`, `Pictures/RecorderZy`) |
| Crash recovery / muxer flush | `ScreenRecorder.startMuxerFlushHeartbeat` + `RecorderApp.onCreate` shutdown hook |
| Battery optimisations prompt | `PermissionsScreen` → `requestIgnoreBatteryOptimizations` |
| Async via Kotlin Coroutines | `CoroutineScope(SupervisorJob() + Dispatchers.Default)` in `ScreenRecorder` and `AudioCaptureMixer` |
| `SYSTEM_ALERT_WINDOW`, `MEDIA_PROJECTION`, `RECORD_AUDIO` | Manifest + runtime polling in `PermissionsScreen` |

---

## Caveats

* `getCpuHeadroom` / `getGpuHeadroom` are loaded reflectively – they return
  `null` on devices that don't expose `SystemHealthManager` headroom probes.
  The app keeps working, the home-screen card just collapses to thermal-only.
* The native floating drawer renders four `ImageView` mini-buttons; the
  Flutter `FloatingDrawer` is the polished version shown when the app is in
  the foreground. The two are wire-compatible.
* The "Show Touches" feature relies on the system-wide indicator being fed
  coordinates from an Accessibility Service. The skeleton service is left
  for the project owner to opt-in to per-deployment.
* Software RNNoise is *not* bundled – the hardware `NoiseSuppressor` effect
  is the production path. The README slot for `librnnoise.so` JNI binding
  is documented in `NoiseSuppressorEffect.kt`.

---

## License

[MIT](LICENSE).
