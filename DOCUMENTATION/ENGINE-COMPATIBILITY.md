# Engine Compatibility Matrix

| Engine | Version | Integration Type | Android ARM64 Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Native ELF64/32, Mmap Hex & Hash | 1.0.0 | JNI C++20 (`libreforge_native.so`) | INTEGRATED | Compiled with NDK C++20 and 16KB ELF page alignment |
| Capstone Disassembler | 5.0.3 | JNI C (`libreforge_native.so`) | INTEGRATED | Supports ARM64, ARM/Thumb, x86, and x86-64 |
| Radare2 Core | 5.9.8 | Bundled ARM64 Executable (`libr2.so`) | INTEGRATED (Full CI build) | Cross-compiled for `aarch64-linux-android26` in CI |
| Google Smali / Baksmali / dexlib2 | 3.0.8 | Pure JVM Library on ART | INTEGRATED | Multi-DEX indexing, Smali disassembly, and re-assembly |
| JADX Core | 1.5.0 | Pure JVM Library on ART | INTEGRATED | Headless single-class decompilation without AWT/Swing |
| AXML & Resource Decoder | 1.0.0 | Kotlin Binary Chunk Parser | PARTIALLY INTEGRATED | Decodes binary AXML; desktop Apktool framework resource recompilation excluded |
| Android `apksig` & Page-Aligner | 8.7.2 | Pure JVM Library on ART | INTEGRATED | Local v1/v2/v3 signing, verification, and 16KB `.so` alignment |
| Androguard (Python) | 4.1.2 | CPython + lxml | UNSUPPORTED | Excluded to avoid 40MB+ Python runtime overhead |
