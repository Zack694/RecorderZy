# RecorderZy

Ultra-lightweight Android 16 (API 36) screen recorder & screenshot app.
Flutter UI + native Kotlin for the heavy lifting.

[![Build APK](https://github.com/Zack694/RecorderZy/actions/workflows/build.yml/badge.svg)](https://github.com/Zack694/RecorderZy/actions/workflows/build.yml)

## Highlights

- **Hardware HEVC encoding** by default, with an opt-in toggle for the new
  Android 16 **APV** codec profile (`video/apv`).
- **ADPF thermal-aware pipeline.** Polls
  `PowerManager#getCpuHeadroom` / `getGpuHeadroom` every 750 ms and
  trims encoder bitrate before frame drops happen.
- **Adaptive Refresh Rate.** Uses `Display#hasArrSupport` and
  `Display#getSuggestedFrameRate` to lock the recorder, encoder surface
  and panel to a synchronised cadence (up to 120 fps).
- **Full pause/resume.** Detaches the `VirtualDisplay` surface from the
  encoder rather than tearing it down, so the same MP4 file is preserved -
  no segmenting, no black frames.
- **Floating overlay**, draggable anywhere on screen, with a real-time
  HH:MM:SS timer pill, animated 4-button drawer (Pause/Resume,
  Screenshot, Settings, Close), and adjustable size & opacity.
- **Hidden from the recording.** The floating handle is added with
  `FLAG_SECURE`, which `MediaProjection` excludes from capture - visible to
  the user, completely absent from the saved video file.
- **Audio FX.** NoiseSuppressor effect on the mic stream + 5 voice-changer
  presets (Normal / Deep / Robot / Helium / Radio comms). Mic only,
  internal-only, or mic+internal mixed.
- **Crash-safe writes.** `SafeMuxer` defers `start()` until both encoders
  publish their format and serialises sample writes; if the process is
  killed mid-stream the partial mp4 still has a valid moov atom.
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
                  ┌────────────────────────────────────┼────────────────────────────────┐
                  ▼                                    ▼                                ▼
        ScreenRecorderEngine                 AudioPipeline                    Screenshotter
        ├─ MediaCodec (HEVC/APV)             ├─ AudioRecord (mic)             ├─ ImageReader
        ├─ VirtualDisplay                    ├─ AudioPlaybackCapture          ├─ JPEG -> MediaStore
        ├─ ADPF / ARR                        ├─ NoiseSuppressor effect
        └─ SafeMuxer (mp4)                   ├─ VoiceChangerDsp
                                             └─ MediaCodec (AAC) ─► SafeMuxer

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
