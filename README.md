# RecorderZy

Ultra-lightweight Android 16 (API 36) screen recorder & screenshot app.
Flutter UI + native Kotlin for the heavy lifting.

[![Build APK](https://github.com/Zack694/RecorderZy/actions/workflows/build.yml/badge.svg)](https://github.com/Zack694/RecorderZy/actions/workflows/build.yml)

## Highlights

- **Reliable `MediaRecorder` capture.** Uses the high-level
  `MediaRecorder` + `MediaProjection` + `VirtualDisplay` pipeline (the same
  approach mainstream recorders use) for maximum device compatibility, with
  an automatic H.264 / lower-frame-rate fallback ladder so it starts even on
  picky OEM encoders.
- **Microphone audio** via `MediaRecorder` (AAC). Internal/system audio is
  not captured by this path.
- **Full pause/resume** through `MediaRecorder.pause()` / `resume()`.
- **Floating overlay**, draggable anywhere on screen, with a real-time
  HH:MM:SS timer pill, animated 4-button drawer (Pause/Resume,
  Screenshot, Settings, Close), and adjustable size & opacity.
- **Hidden from the recording.** The floating handle is added with
  `FLAG_SECURE`, which `MediaProjection` excludes from capture - visible to
  the user, completely absent from the saved video file.
- **Microphone capture** recorded as AAC alongside the video. (The voice
  changer / noise-suppression DSP and internal-audio mixing from earlier
  builds are not applied on the MediaRecorder path.)
- **Crash-safe writes.** Recording is written to app cache then published to
  the public gallery via `MediaStore` only after a clean stop.
- **Strict modern Android compliance.**
  - Foreground service types `mediaProjection|microphone|specialUse`
  - Re-requests `createScreenCaptureIntent()` for every new session
  - `MediaStore` publishes into the public `Movies/RecorderZy` and
    `Pictures/RecorderZy` albums (no MANAGE_EXTERNAL_STORAGE)
  - Edge-to-edge enforced; Flutter consumes insets via `SafeArea`
  - Battery-optimisation exemption prompt baked into the home screen

## Architecture

```
flutter UI ──► RecorderBridge ──► MethodChannel ──► RecorderChannel (Kotlin)
                                                       │
                                                       ▼
                          ScreenRecorderService (foreground, mediaProjection|microphone)
                                                       │
                  ┌────────────────────────────────────┴───────────────────────────┐
                  ▼                                                                  ▼
        ScreenRecorderEngine                                              Screenshotter
        ├─ MediaRecorder (H.264/HEVC + AAC mic)                           ├─ ImageReader
        ├─ VirtualDisplay (MediaProjection)                               └─ JPEG -> MediaStore
        └─ writes MP4 to cache -> MediaStore (Movies/RecorderZy)

flutter UI ──► OverlayChannel ──► FloatingOverlayService    (FLAG_SECURE window)
                                       └─► RecorderStateBus  (timer + phase)
```

## Required runtime permissions

| Permission                                     | Used for                              |
| ---------------------------------------------- | ------------------------------------- |
| `RECORD_AUDIO`                                 | Microphone capture                    |
| `SYSTEM_ALERT_WINDOW`                          | Floating control overlay              |
| `POST_NOTIFICATIONS`                           | Recording / overlay foreground notif  |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION`          | Screen capture FG service             |
| `FOREGROUND_SERVICE_MICROPHONE`                | Mic FG service (Android 14+)          |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`         | High-FPS recording stability          |

## Building

CI builds the APK for every push via GitHub Actions
(`.github/workflows/build.yml`). Locally:

```bash
flutter pub get
( cd android && gradle wrapper --gradle-version 8.10.2 --distribution-type bin )
flutter build apk --release --target-platform=android-arm64
```

The CI workflow exposes both the debug and release APKs as artifacts on
each successful run.

## Notes & caveats

- **MediaProjection consent per session.** Android 14+ invalidates a
  projection token after a single VirtualDisplay lifecycle, so we
  re-launch `ProjectionRequestActivity` (which simply wraps
  `createScreenCaptureIntent`) every time the user taps Start.
- **Voice changer** uses lightweight DSP colourisation (low-shelf tremolo,
  comb feedback, ring-modulator, band-pass + soft-clip). It is *not* a
  true PSOLA pitch-shifter - this avoids the AV-sync drift that real
  time-stretching would introduce.
- The `flutter_colorpicker` package is used for the theme colour pickers;
  no custom HSV picker is shipped.

## License

MIT - see [`LICENSE`](LICENSE).
