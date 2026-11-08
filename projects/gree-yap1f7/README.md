# Gree YAP1F7 Android infrared remote — 1:2 UI

An **offline Android IR remote** based on the owner's Gree YAP1F7 photographs and interactive fullscreen 1:2 HTML/SVG mockup. The WebView is fully bundled; no network, cloud or Wi-Fi is needed.

## Build on GitHub Actions

Upload this directory as the repository root and push to `main`. Open **Actions → Build Gree YAP1F7 APK → latest run → Artifacts → Gree-YAP1F7-debug-APK**. The workflow installs JDK 17, Android SDK 35 and Gradle 8.10.2, runs unit tests and builds a debug APK. `workflow_dispatch` also provides **Run workflow**.

**Build via CLI:** `gradle --no-daemon testDebugUnitTest assembleDebug` (with JDK 17, Android SDK 35, Gradle 8.10.2).

## IR functionality / limitations

- Native `ConsumerIrManager` broadcasts **candidate 8-byte Gree 38 kHz state frames** for Power, Mode, 16–30 °C, Fan, Turbo, I Feel, Swing V/H, Light, WiFi, Sleep, and OFF countdown.
- Press and hold the **GREE logo** for 1 second to switch the YAW1F/YBOFB model bit when testing if your AC doesn't respond.
- **The YAP1F7 remote is confirmed by its back label, but the IR protocol has not been captured or validated on the real air conditioner.** An APK build does not establish that the unit accepts these frames.
- CLOCK is local display time only. HEALTH/AIR is visual-only because no confirmed command is available. Timer currently supports OFF countdown, not ON scheduling. Missing LCD functions remain dimmed at 30% in the UI.
- The transport is one-way, so changing settings on the physical handset won't update this app.
- Android with an IR blaster is required (and hardware must permit ConsumerIrManager at ~38 kHz). The app has no INTERNET permission.

## Source and UI

- `app/src/main/assets/index.html`: same 1:2 touchscreen design as the handoff.
- `index.html`: standalone browser preview (not an IR transmitter).
- `app/src/main/java/com/example/greeyap1f7/MainActivity.kt`: WebView/Android bridge.
- `app/src/main/java/com/example/greeyap1f7/GreeEncoder.kt`: candidate Gree packet encoder.
- `app/src/test/.../GreeEncoderTest.kt`: checks payload packing, timing and checksum.
- `references/`: original front/back photos, screenshot, and all LCD segments visible.

### Testing on the real AC

1. Use an Android phone with an IR emitter; aim the IR LED at the AC.
2. Test ON/OFF; if no response, hold the GREE logo to switch variants and retry.
3. Check temperature, mode, fan and swing; if neither variant works, capture the original YAP1F7's IR signal with an ESP32/IR receiver or logic analyzer to identify the correct encoding.

Sources: [Gree IR implementation](https://github.com/crankyoldgit/IRremoteESP8266/blob/master/src/ir_Gree.cpp) · [Gree protocol bitfields](https://github.com/crankyoldgit/IRremoteESP8266/blob/master/src/ir_Gree.h) · [Android IR manager](https://developer.android.com/reference/android/hardware/ConsumerIrManager).

## Adaptive phone screen and startup behavior

- The SVG control geometry remains 1:2; the casing finish is now painted as a continuous full-screen gradient for taller and wider phones, with no hard horizontal or vertical panel seam.
- Every app launch defaults to **Power OFF** (the last temperature, mode, and other settings remain cached). Launching does **not** send an IR command.
