# RE FORGE

RE FORGE (`org.bimz.reforge`) is a native Android ARM64 reverse-engineering, binary inspection, APK analysis, and workspace rebuild workstation built for on-device operation without a desktop workstation.

## Core Architecture

- **Application ID**: `org.bimz.reforge`
- **Target ABI**: `arm64-v8a` (`minSdk = 26`, `targetSdk = 35`)
- **UI Layer**: Jetpack Compose Material 3 workstation with dark and light WCAG AA themes
- **Native Engine (`libreforge_native.so`)**: C++20 ELF64/ELF32 header/section/symbol/dependency parser, `pread64` paged hex reader/patcher, SHA-256/CRC32 hash engine, and Capstone multi-architecture disassembler via JNI
- **DEX & Decompilation**: Google `smali-dexlib2`, `smali-baksmali`, `smali` assembler (`3.0.8`), and headless `jadx-core` (`1.5.0`)
- **APK & Signing**: Native binary AXML decoder, 4-byte and 16KB `.so` page-aligned ZIP packager, and Google `apksig` (`8.7.2`) v1/v2/v3 signer and verifier
- **Radare2 ARM64 Bridge**: Executes allowlisted non-interactive queries against bundled `lib/arm64-v8a/libr2.so`
