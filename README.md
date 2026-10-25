# Luxio Android

Standalone native Compose app. UI changes remain deferred until iOS approval.

Install Java 21 and the Android SDK, then set `ANDROID_HOME` or a local `sdk.dir` in `local.properties`.

```sh
GRADLE_USER_HOME="$PWD/.sdk/gradle" ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

An existing debug signing identity may be kept in `.sdk/android-debug.keystore`. If absent, Gradle uses its standard debug keystore. No neighboring Luxio repository is required.

Saved animations are listed on the light screen. Firmware build 14 exposes active animation parameters as native checkbox/toggle controls, sliders, and numeric inputs. Values change without restarting playback; **Save parameters** explicitly saves them with the active saved animation.

Animation presets include preview palettes and live parameters. `Slider` and `Checkbox` declarations require firmware build 21 or newer; `Number` selects a numeric input. Older firmware parameter metadata continues to display numeric values as sliders. `Boolean` remains a legacy alias for `Checkbox`.

Firmware build 27 adds Relay, Relay GPIO and Invert Relay to light settings.
Relay defaults to disabled, and its GPIO must differ from Data Pin. GL-C-016WL-D
revisions use GPIO12 without inversion (older manual) or GPIO18 with inversion
(current manufacturer manual); data stays on GPIO16. Check the controller
revision. Older firmware must be updated before enabling it.

The physically tested GL-C-016WL-D unit requires Relay GPIO18 with inversion off,
with data on GPIO16. Verify polarity on the actual unit: app On must supply strip
power. The model name alone does not determine the relay revision or polarity.

Build 28 hides Matter settings when the firmware reports `matter_disabled`. Older
firmware without that capability flag keeps its existing Matter controls.

Build 34 adds Wi-Fi/Matter enable switches, with confirmation before disabling the HTTP connection. Radio changes restart the controller and preserve credentials/pairings. SK6812 is selectable as an RGBW strip.
