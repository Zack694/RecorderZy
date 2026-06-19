# Recording Fixes Applied

## Summary
Fixed multiple crash and recording failure issues in RecorderZy. The app now has better error handling, logging, and user notifications.

## Critical Issues Fixed

### 1. **ScreenRecorderEngine Crashes**
**Problem:** Encoder initialization could fail silently or crash with cryptic errors.

**Fixes:**
- ✅ Added cache directory validation before creating output files
- ✅ Improved video encoder fallback logic with better error messages
- ✅ Added null checks for input surface creation
- ✅ Enhanced error propagation (re-throw instead of catching and stopping)
- ✅ Added validation for invalid video dimensions
- ✅ Better cleanup on partial initialization failures
- ✅ More detailed logging at each step

### 2. **AudioPipeline Silent Failures**
**Problem:** Audio recording could fail due to missing permissions but recording would continue without audio, confusing users.

**Fixes:**
- ✅ Added SecurityException handling for RECORD_AUDIO permission
- ✅ Better AudioRecord state checking and logging
- ✅ Try-catch around AudioRecord.startRecording() to prevent crashes
- ✅ Added detailed logs showing which audio sources initialized successfully
- ✅ Graceful handling when internal audio is requested on Android < Q

### 3. **SafeMuxer Race Conditions**
**Problem:** The muxer's track counting could get out of sync when audio is disabled, causing hangs.

**Fixes:**
- ✅ Changed `expectedTracks` from constant to variable
- ✅ Fixed `expectAudio()` to properly adjust track count instead of incrementing
- ✅ Added logging for all state transitions
- ✅ Better null/invalid track checks in `writeSample()`
- ✅ Added guards against adding tracks after muxer starts

### 4. **User Experience**
**Problem:** Users had no idea why recording failed - just silent crashes or no output.

**Fixes:**
- ✅ Added error notifications with specific messages:
  - "Video encoder unavailable" for codec issues
  - "Invalid video settings" for dimension problems
  - "Storage unavailable" for cache directory issues
  - "No video data captured" for empty files
- ✅ Added success notification when recording saves properly
- ✅ Better file validation (checks existence, size > 0)
- ✅ Log file size when publishing

### 5. **Encoder Drain Loop Robustness**
**Problem:** Video encoder drain could fail without proper logging.

**Fixes:**
- ✅ Throw exceptions instead of silent return when codec/muxer missing
- ✅ Added logging when encoder format is ready
- ✅ Log when EOS (End of Stream) is reached
- ✅ Better error messages in drain loop

## About FFmpeg

**Does RecorderZy need FFmpeg?** 
**NO!** RecorderZy uses Android's native APIs:
- **MediaCodec** for video encoding (HEVC/H.264/APV)
- **MediaMuxer** for MP4 container creation
- **AudioRecord** for audio capture
- **MediaProjection** for screen capture

This is actually **better** than FFmpeg because:
- Lower battery consumption
- Hardware acceleration support
- Smaller APK size
- Better Android integration

## Testing Recommendations

To test the fixes:

1. **Test basic recording:**
   - Start a recording
   - Check logcat for "Recording engine started successfully"
   - Stop recording
   - Verify "Video saved successfully to:" message
   - Check notification appears

2. **Test without microphone permission:**
   - Revoke RECORD_AUDIO permission
   - Try recording with mic audio
   - Should see "RECORD_AUDIO permission not granted" in logs
   - Recording should still work (video only or internal audio)

3. **Test error scenarios:**
   - Try recording with device in low storage
   - Should see appropriate error notification

4. **Check logcat filters:**
   ```bash
   adb logcat | grep -E "ScreenRecorderEngine|AudioPipeline|SafeMuxer|ScreenRecorderService"
   ```

## Key Log Messages to Look For

**Success indicators:**
```
ScreenRecorderEngine: Video encoder configured: video/hevc @ 1080x1920 60fps
ScreenRecorderEngine: Recording engine started successfully
AudioPipeline: Microphone AudioRecord initialized successfully
AudioPipeline: Audio setup complete - Mic: true, Internal: false, Mode: MIC
SafeMuxer: Muxer started with 2 tracks
ScreenRecorderService: Video saved successfully to: content://...
```

**Common error messages (now with better descriptions):**
```
ScreenRecorderEngine: All video encoders failed. Last error: ...
AudioPipeline: RECORD_AUDIO permission not granted
SafeMuxer: Cannot add track after muxer started
ScreenRecorderService: Recording failed - Video encoder unavailable
```

## Files Modified

1. `android/app/src/main/kotlin/com/recorderzy/app/recorder/ScreenRecorderEngine.kt`
   - Enhanced validation and error handling
   - Better encoder configuration
   - Improved drain loop

2. `android/app/src/main/kotlin/com/recorderzy/app/recorder/AudioPipeline.kt`
   - Better permission handling
   - Enhanced error logging
   - Safer capture loop

3. `android/app/src/main/kotlin/com/recorderzy/app/recorder/SafeMuxer.kt`
   - Fixed track counting logic
   - Added comprehensive logging
   - Better state validation

4. `android/app/src/main/kotlin/com/recorderzy/app/recorder/ScreenRecorderService.kt`
   - Added user-facing error notifications
   - Better file validation
   - Success notifications

## Next Steps

If you still experience crashes:

1. **Capture logcat:**
   ```bash
   adb logcat -v time *:E ScreenRecorder*:D AudioPipeline:D SafeMuxer:D > crash.log
   ```

2. **Check for specific errors:**
   - Look for "All video encoders failed" → codec compatibility issue
   - Look for "RECORD_AUDIO permission" → permission issue
   - Look for "Storage unavailable" → disk space issue

3. **Test on different devices:**
   - The fixes include codec fallbacks (HEVC → H.264) for compatibility
   - Some older devices may only support H.264

## Remaining Known Limitations

- Internal audio capture requires Android Q (API 29+)
- Microphone requires RECORD_AUDIO permission
- Screen recording requires user consent via MediaProjection dialog
- Some devices may not support HEVC encoding (falls back to H.264)


---

# Round 2: "Nothing happens" on Android 14+/16 (Poco X6 5G)

This round fixes the actual reason recording and screenshots did nothing on
Android 14, 15, and 16 devices (including HyperOS / Poco X6 5G).

## Root Cause #1 (the killer): Microphone FGS type without permission

`ScreenRecorderService` always started the foreground service with the
`microphone` service type:

```kotlin
t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE   // unconditional
```

On Android 14+ (API 34+), `startForeground()` with the `microphone` type
throws `SecurityException` when `RECORD_AUDIO` has **not** been granted. That
happens in STEP 1 of `onStartCommand`, before any recording work — so the
service immediately hit its catch block and called `stopSelf()`.

Result: **no recording, no screenshot, and no visible crash** — exactly the
reported symptom. A fresh install hasn't granted the mic permission, so
everything silently failed.

**Fix:** `foregroundTypes(action)` now only adds the `microphone` type when:
1. It's a recording session (never for one-shot screenshots),
2. `RECORD_AUDIO` is actually granted, and
3. The selected audio mode actually uses the mic (MIC or BOTH).

`mediaProjection` is always safe to declare. Recording now starts even
without mic permission (video-only / internal audio), instead of dying.

## Root Cause #2: Screenshot never registered a projection callback

Android 14+ requires a `MediaProjection.Callback` to be registered **before**
`createVirtualDisplay()`, or it throws `IllegalStateException`. The recording
path did this; the standalone screenshot path did not — so screenshots threw
and silently failed.

**Fix:** `handleScreenshot()` now registers the callback right after seeding
the projection from the token holder.

## Root Cause #3: The lying "saved" toast

The Flutter UI showed "Screenshot saved to Pictures/RecorderZy" whenever
screen-capture permission was *granted* — not when a file was actually
written. Since capture was failing (causes #1/#2), the file never existed.

**Fix:**
- The native service now posts a real success/failure notification based on
  whether the image URI was actually created.
- The Flutter toast no longer claims success; it says the capture was
  requested and to check notifications.
- `Screenshotter` got a 4-second timeout fallback so it can't hang the
  foreground service forever if a frame never arrives, plus double-callback
  protection.

## Extra UX fix: request mic/notification permission before recording

`_startRecording` now requests `POST_NOTIFICATIONS` and (when the audio mode
needs it) `RECORD_AUDIO` up front, so a first-time user actually captures
microphone audio instead of recording silently.

## Files changed (Round 2)

1. `android/.../recorder/ScreenRecorderService.kt` — conditional FGS types,
   screenshot callback registration, real screenshot result notifications.
2. `android/.../recorder/Screenshotter.kt` — timeout fallback + safe cleanup.
3. `lib/ui/home_screen.dart` — proactive permission requests, honest toast.

## How to verify on the Poco X6 5G

1. Fresh install, hit **Start recording**, pick "Entire screen" → it should
   now actually record (you'll see the timer and a recording notification).
2. Stop → check for a "Recording saved" notification and the file under
   `Movies/RecorderZy`.
3. Hit **Screenshot** → check for a "Screenshot saved" notification and a file
   under `Pictures/RecorderZy`.
4. If anything still fails, capture logs:
   ```bash
   adb logcat | grep -E "ScreenRecorderService|ScreenRecorderEngine|Screenshotter|AudioPipeline"
   ```


---

# Round 3: Recording fails after consent + real (non-projection) screenshots

After Round 2 the service started correctly, but two device-specific problems
remained on the Poco X6 5G (Android 16, screen 1220x2712).

## Recording: encoder dimension alignment

The screen resolution **1220 x 2712** is not a multiple of 16. Hardware
H.264/HEVC encoders (Dimensity 6080) require width/height aligned to their
reported alignment (usually 16). The unaligned size made `MediaCodec.configure()`
/ `createInputSurface()` fail right after consent, so recording failed.

**Fix (`ScreenRecorderEngine.configureVideoEncoder`):**
- Query the chosen encoder's `VideoCapabilities` and snap the requested size
  to its `widthAlignment`/`heightAlignment`, then clamp into the supported
  width/height range.
- Verify with `isSizeSupported()` before configuring; fall through to the next
  codec (HEVC -> H.264) if not.
- Clamp the frame rate and bitrate to the encoder's supported ranges too.
- The `VirtualDisplay` now uses these aligned dimensions so it matches the
  encoder input surface exactly.

For the Poco X6 5G this records at 1216x2704 (a 4px/8px crop) instead of
failing.

## Screenshots now use a REAL screenshot (no screen recording)

Screenshots no longer go through MediaProjection at all. They use the
`AccessibilityService.takeScreenshot()` API via the existing
`TouchIndicatorService`, so there is **no "Start recording or casting" consent
dialog** for a screenshot.

**Changes:**
- `res/xml/touch_indicator_service.xml`: added `android:canTakeScreenshot="true"`
  (required, or `takeScreenshot()` throws SecurityException).
- `TouchIndicatorService`: holds a static `instance`, exposes
  `captureScreenshot()` that grabs the screen, scales/encodes to JPEG, and
  saves to `Pictures/RecorderZy`.
- `RecorderChannel.takeScreenshot`: routes to the accessibility service and
  returns `saved` / `failed` / `needs_accessibility` / `unsupported`.
- Flutter `home_screen`: shows a real result and, when the accessibility
  service isn't enabled, a SnackBar with an "Enable" action that opens
  Accessibility settings.
- Floating overlay screenshot button: same accessibility path.

Trade-off: the user must enable the "RecorderZy" accessibility service once.
This is the only way for a normal app to take a true full-screen screenshot
without a screen-record consent prompt.

## Files changed (Round 3)

1. `android/.../recorder/ScreenRecorderEngine.kt` — encoder size/fps/bitrate
   alignment + aligned VirtualDisplay.
2. `android/.../overlay/TouchIndicatorService.kt` — accessibility screenshot.
3. `android/.../channels/RecorderChannel.kt` — screenshot routes to a11y.
4. `android/.../overlay/FloatingOverlayService.kt` — overlay screenshot a11y.
5. `android/.../res/xml/touch_indicator_service.xml` — canTakeScreenshot.
6. `lib/core/platform/recorder_bridge.dart` — ScreenshotOutcome.
7. `lib/ui/home_screen.dart` — outcome handling + enable-accessibility prompt.


---

# Round 4: Reverted screenshot to MediaProjection + removed "Show touches"

Per user request, Round 3's accessibility-based screenshot was reverted and
the touch-indicator feature was removed entirely (it required enabling an
accessibility service, which nearly soft-locked the device).

## Screenshots: back to the standard MediaProjection approach

- Screenshots once again use MediaProjection (single captured frame via
  `Screenshotter`/`ScreenRecorderService`), the same approach standard
  screen-recorder apps use. No accessibility service required.
- `RecorderChannel.takeScreenshot` -> `RecorderLauncher.takeScreenshot`,
  returns a plain bool again.
- Floating overlay screenshot button uses the same MediaProjection path.

## "Show touches" / touch indicator removed completely

Deleted / cleaned up:
- `TouchIndicatorService.kt` (the AccessibilityService) - deleted.
- `res/xml/touch_indicator_service.xml` - deleted.
- AndroidManifest: removed the accessibility `<service>` entry.
- `proguard-rules.pro`: removed the keep rule for the deleted service.
- `RecorderConfig`: removed the `showTouches` field (data class, defaults,
  fromMap, fromIntent, applyExtras) and `RecorderLauncher.autoConfig`.
- `OverlayChannel`: removed `openAccessibilitySettings`.
- `SettingsChannel`: removed `isAccessibilityEnabled`.
- Flutter `RecorderSettings`/`SettingsController`: removed the `showTouches`
  setting and its persisted key.
- Flutter UI: removed the "Show touches" settings card, the "Open
  accessibility settings" button, the accessibility status row on the home
  screen, and the `ScreenshotOutcome` plumbing.

The app no longer requests or uses any accessibility capability at all.
