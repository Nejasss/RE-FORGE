# Feature Reference

- **Home Workstation**: Displays app version, Android SDK version, device ABIs, engine count, workspace storage usage, recent workspaces, and analysis history.
- **Workspace Manager**: Preserves imported originals read-only under `original/`, creates mutable copies in `working/`, tracks staged modifications, computes SHA-256/SHA-1/MD5/CRC32, and compares two workspaces.
- **APK Inspector**: Decodes binary `AndroidManifest.xml`, lists components (`activity`, `service`, `receiver`, `provider`) with `exported` flags and intent filters, categorizes ZIP entries, inspects resources, and verifies v1/v2/v3 signatures.
- **DEX & Smali / JADX Viewer**: Indexes multi-DEX containers, disassembles classes to Smali, edits and diffs Smali in the workspace, and decompiles individual classes to Java via JADX Core.
- **Binary & ELF Analyzer**: Parses ELF64/32 headers, segments, sections, symbols, imports, `DT_NEEDED` libraries, and strings; disassembles code offsets with Capstone and runs allowlisted Radare2 JSON queries.
- **Paged Hex Viewer & Patcher**: Reads arbitrary file sizes in 256-byte pages via native `pread64`, searches hex byte sequences, and applies bounded byte patches with undo/redo.
- **Rebuild, Sign & Verify**: Rebuilds modified APKs with 16KB `.so` alignment, signs locally via `apksig` (`AndroidKeyStore` or PKCS#12), and runs post-build verification checks.
