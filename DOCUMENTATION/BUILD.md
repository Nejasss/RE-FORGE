# Build Instructions

## Automated Build via GitHub Actions (Recommended)

1. Push this repository to GitHub.
2. Open the **Actions** tab and run **Build RE FORGE ARM64 APK & Release Archive** (`.github/workflows/android-build.yml`).
3. The workflow fetches Capstone `5.0.3`, cross-compiles Radare2 `5.9.8` for `aarch64-linux-android26`, runs `./gradlew testDebugUnitTest`, builds and signs `RE-FORGE.apk`, verifies signatures with `apksigner`, and uploads `RE-FORGE-FULL-ARM64-v1.0.0.zip`.

## Local Build with Android SDK + NDK

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/27.2.12479018
./scripts/build-radare2-arm64.sh app/src/main/jniLibs/arm64-v8a
./gradlew testDebugUnitTest assembleRelease
```
