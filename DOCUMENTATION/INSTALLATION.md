# Installation Guide

1. Download `RE-FORGE-FULL-ARM64-v1.0.0.zip` or `RE-FORGE.apk` from the GitHub Actions workflow artifacts.
2. Verify the SHA-256 digest of `RE-FORGE.apk` against `SHA256SUMS.txt` inside the release archive:
   ```sh
   sha256sum -c SHA256SUMS.txt
   ```
3. Transfer `RE-FORGE.apk` to your Android ARM64 (`arm64-v8a`, Android 8.0+) device.
4. Open the APK in your Android file manager and allow installation from unknown sources when prompted, or install via ADB:
   ```sh
   adb install -r RE-FORGE.apk
   ```
5. Launch **RE FORGE** and import target files via the Android Storage Access Framework (`Open APK`, `Open DEX`, `Open ELF / SO`, or `Open Binary`).
