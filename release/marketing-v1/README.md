# Luxio Android store screenshots

Five 1620 × 2880 (9:16), opaque promotional PNGs matching the approved iOS typography, colors and copy, captured from the actual Android app with isolated example devices. All five approved images were uploaded to Google Play and submitted with version 1.0.0 on 28 October 2026. This is the only retained Android screenshot set; `raw/` contains its five original captures.

Run `./render.sh` on macOS with Google Chrome installed to render the layouts. `raw/` contains the native emulator captures. Nunito fonts and their license are in `fonts/`.

To recapture, temporarily copy `MarketingScreenshots.kt` into `app/src/androidTest/java/lighting/luxio/`, build the debug application and test APKs, and install both on an isolated emulator. Run `adb shell am instrument -w -e class lighting.luxio.MarketingScreenshots lighting.luxio.test/androidx.test.runner.AndroidJUnitRunner`. Pull `/sdcard/Android/data/lighting.luxio/files/marketing/` into `raw/` before uninstalling the app. Remove the temporary test file afterward. Gradle's connected test task may uninstall the app and remove its captures on completion, so direct instrumentation is used for export.

The fixture uses local HTTP controllers, a native Bluetooth indicator for Studio, sample network details and a nonfunctional Matter QR payload. It does not discover or control physical devices. The normal application source is unchanged.

The 9:16 layout follows Google Play’s recommended screenshot aspect ratio: https://support.google.com/googleplay/android-developer/answer/9866151

For the approved list composition, apply `capture-order.patch` only while capturing to put the off device last; reverse it after capture. The other four PNGs remain byte-for-byte identical to the approved images.
