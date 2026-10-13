# WiFi Gauge (Android 16 / API 36)

A live WiFi signal-strength **gauge** for Android. Built with **zero dependencies and no Gradle** —
plain Java + the Android platform SDK, compiled by hand with `aapt2 → javac → d8 → zipalign → apksigner`.

Result: **`wifigauge.apk`** (≈25 KB, signed, v2+v3 schemes).

## What it does

- **Analog gauge** spanning −100…−40 dBm with color zones (red → emerald), tick marks, dBm labels,
  a smoothed needle, and a thin **instant "live" needle** that always sits exactly on the latest reading.
- **Very sensitive by design:**
  - reacts **instantly** to `WifiManager.RSSI_CHANGED` broadcasts (the OS fires these the moment the
    Wi-Fi driver reports a signal change),
  - plus a **100 ms poller** (10 reads/sec) as a fail-safe,
  - the needle uses frame-rate-independent exponential smoothing with an adjustable time constant
    (default ≈60 ms — snappy), controlled by the **Sensitivity** slider,
  - a **30-second scrolling sparkline** (300 samples @ 100 ms) shows fine fluctuations,
  - live **Δ dBm** ticker (▲/▼ vs. the previous sample) and a 4-bar signal indicator.
- Falls back to the **strongest nearby network** (from scan results) when not connected.
- Edge-to-edge aware (`fitsSystemWindows` + transparent system bars, enforced on API 35+),
  keeps the screen on while open.
- Runtime permissions: `NEARBY_WIFI_DEVICES` (Android 13+) / `ACCESS_FINE_LOCATION` (<13).

## Build

```bash
./build.sh        # needs JDK 17 + Android SDK (platforms;android-36, build-tools;36.0.0)
```

The script sets `JAVA_HOME` to the Homebrew OpenJDK 17 by default and looks for the SDK in
`~/android-sdk` (override with `ANDROID_SDK`).

## Install

```bash
adb install -r wifigauge.apk
```

or copy the APK to the phone and open it. On Android 13+ allow the **Nearby devices** (Wi-Fi)
permission on first launch; on Android 12 and below allow **Location**.

## Project layout

```
AndroidManifest.xml                          minSdk 24 · targetSdk 36 · package com.example.wifigauge
res/layout/activity_main.xml                 main screen
src/com/example/wifigauge/MainActivity.java  receivers, 100 ms poller, permission flow, sensitivity prefs
src/com/example/wifigauge/GaugeView.java      analog gauge (2 needles, zones, readout)
src/com/example/wifigauge/SparklineView.java  history strip (30 s)
preview.html                                 static visual mock of the UI
```