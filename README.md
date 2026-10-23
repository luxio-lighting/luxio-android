# Luxio Android

Standalone native Compose app. UI changes remain deferred until iOS approval.

Install Java 21 and the Android SDK, then set `ANDROID_HOME` or a local `sdk.dir` in `local.properties`.

```sh
GRADLE_USER_HOME="$PWD/.sdk/gradle" ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

An existing debug signing identity may be kept in `.sdk/android-debug.keystore`. If absent, Gradle uses its standard debug keystore. No neighboring Luxio repository is required.

Saved animations are listed on the light screen. Firmware build 14 exposes active animation parameters as native checkbox/toggle controls, sliders, and numeric inputs. Values change without restarting playback; **Save parameters** explicitly saves them with the active saved animation.

Animation presets include preview palettes and live parameters. `Slider` and `Checkbox` declarations require firmware build 21 or newer; `Number` selects a numeric input. Older firmware parameter metadata continues to display numeric values as sliders. `Boolean` remains a legacy alias for `Checkbox`.
