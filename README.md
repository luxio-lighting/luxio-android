# Luxio Android

Native Kotlin/Jetpack Compose app, matching the iOS layout, warm gradient background, compact live device cards, live-color control header and circular preset grid. Android uses Material 3 sheets, segmented controls, switches, sliders, dialogs and permissions, with Luxio color previews and a custom color wheel.

## Build and run

Install Java 17+, Android SDK 36, NDK 28.2.13676358 and CMake 3.22.1. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`. Gradle can install missing native dependencies when SDK licenses have been accepted.

```sh
GRADLE_USER_HOME="$PWD/.sdk/gradle" ./gradlew --no-daemon testDebugUnitTest assembleDebug
GRADLE_USER_HOME="$PWD/.sdk/gradle" ./gradlew --no-daemon connectedDebugAndroidTest lintDebug
```

The app supports Android 8+ on ARM64 and x86-64. Native libraries support 16 KB pages. An existing debug signing identity may be kept in `.sdk/android-debug.keystore`; otherwise Gradle uses its standard debug key. No neighboring Luxio repository is needed to build.

The dedicated development AVD is `Luxio_Pixel`, using the Android 16 / API 36 Google APIs ARM64 image. Its local files are ignored under `.sdk/avd`.

```sh
ANDROID_AVD_HOME="$PWD/.sdk/avd" "$ANDROID_HOME/emulator/emulator" -avd Luxio_Pixel -gpu host
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

## iOS feature parity

| Area | Android implementation |
| --- | --- |
| Discovery | Verified mDNS and cloud discovery, cached devices, pull-to-refresh, foreground polling, manual IP/hostname entry |
| Bluetooth | Luxio encrypted GATT service, runtime permissions, bounded LXP1 framing, serialized chunk writes, disconnect/timeout handling, read-only Wi-Fi/BLE fallback, identity-checked return to Wi-Fi |
| Home | Live color/gradient/Lua previews, power and brightness controls, connection/offline state |
| Add Luxio | Build-guide link, IP address form with inline errors, subtle virtual-device action |
| Color | HSV wheel, 24 iOS color presets; iOS-style circular color/white selector only for supported strips |
| Gradients | 2–8 editable stops, 24 iOS presets, save/apply/search/rename/delete |
| Animations | Ten current iOS presets, RAM-only playback, native Lua previews, live checkbox/slider/numeric parameters, local per-device/per-animation preferences and reset |
| Saved libraries | Inline color, gradient and animation grids matching iOS, Save tiles, long-press save/rename/delete menus, search for larger libraries; paginated loading and metadata-preserving edits |
| Virtual device | Persistent local settings and libraries using the same protocol and UI; real Lua animation playback |
| Settings | Consistent section cards with one setting per row: connection first, name, LED strip/count/data GPIO, separate Relay section, Wi-Fi and Matter enable switches, firmware build |
| Wi-Fi | Scan and strongest-network ordering, hidden/open networks, password validation/visibility, connection progress, SSID/IP/gateway/DNS; confirmation before disabling the active Wi-Fi connection |
| Matter | Pairing QR code and copyable setup code; enable switch and capability gating |
| Updates | Check, Install sheet, progress, recovery errors/link; Done only after the expected build reconnects |
| Accessibility | Named controls, semantic switches and adjustable wheels, system haptics at interaction, previews paused for background/off/reduced motion |

Hardware/radio settings use the existing firmware configuration API, which persists settings and restarts the controller to release disabled Wi-Fi/Matter resources. Normal color, gradient and preset animation changes use RAM operations; they do not create saved device files. Simulator persistence is local Android storage.

SK6812 and WS2814 use RGBW; WS2805 uses RGB plus warm/cool white; WS2812 uses RGB. New virtual devices default to WS2805, matching iOS; their strip type remains configurable in settings. Candle uses the dedicated white channels when available.

## Validation

- 15 host tests cover canonical protobuf interoperability, radio defaults, BLE frame boundaries, catalog metadata, simulator persistence, libraries and animation-state regressions.
- 23 emulator tests exercise actual Compose interactions, warm/cool and single-white channels, saved libraries, settings, Wi-Fi confirmation, the HTTP transport, network setup, Matter QR/copy and OTA progress with a local controller fixture.
- Native tests run all ten presets across all four strip types at 1, 60 and 1,024 LEDs, verify dedicated-white Candle output, parameter changes and Lua resource/error boundaries.
- Screenshots under `screenshots/` are captured from the Android emulator. Home shows read-only discovery of existing controllers; editor/settings images use the virtual device; Wi-Fi and update images use the isolated loopback controller fixture.

The emulator cannot qualify real Android Bluetooth pairing, radio range, vendor-specific background behavior or haptic feel. Those still need a physical Android phone. The tests never mutate a controller found on the user's network; hardware operations use a loopback test controller.

## Animation engine

`app/src/main/cpp/AnimationRuntime.c` and the vendored Lua 5.4.8 sources originate from the iOS preview engine. Each visible animation owns a bounded runtime on a worker dispatcher; it is released when the preview stops. Previews use the actual script and parameters, with a static palette fallback on errors. Lua is sandboxed without OS, IO, package loading or dynamic code loading. The MIT license is included in `app/src/main/cpp/lua/LICENSE.html` and in the app assets.
