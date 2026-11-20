# Troubleshooting

- **Radare2 shows AVAILABLE instead of INTEGRATED**: Your APK was compiled locally without running `scripts/build-radare2-arm64.sh`. Run the GitHub Actions workflow or execute `./scripts/build-radare2-arm64.sh app/src/main/jniLibs/arm64-v8a` before Gradle assembly.
- **Capstone returns 'not linked in this build configuration'**: Ensure `CAPSTONE_SRC_DIR` points to the Capstone 5.0.3 C source tree when invoking CMake/Gradle.
- **App fails to install over an existing app on Android**: An APK re-signed with RE FORGE's local debug key has a different signing certificate than the original store/developer build. Uninstall the previous package first before installing the re-signed debug APK.
- **JADX decompilation fails on obfuscated method**: Obfuscated control-flow graphs can fail Java decompilation. Switch to the **Smali Editor** tab in the DEX screen to inspect exact Dalvik bytecode instructions.
