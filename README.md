# Luxio Android

Standalone native Compose app. UI changes remain deferred until iOS approval.

Install Java 21 and the Android SDK, then set `ANDROID_HOME` or a local `sdk.dir` in `local.properties`.

```sh
GRADLE_USER_HOME="$PWD/.sdk/gradle" ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

An existing debug signing identity may be kept in `.sdk/android-debug.keystore`. If absent, Gradle uses its standard debug keystore. No neighboring Luxio repository is required.
