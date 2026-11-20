# Security Architecture & Boundaries

- **Authorized Local Analysis Only**: RE FORGE is designed for authorized security research, debugging, educational inspection, and personal app development. It does not ship automated anti-tamper bypass scripts or credential-harvesting modules.
- **No Network Telemetry**: Zero background network sockets or analytics SDKs are included.
- **Scoped File Access**: Files are imported explicitly by the user via Android Storage Access Framework (`OpenDocument`) into app-private `filesDir/workspaces/<projectId>/`.
- **ZIP Slip Protection**: `SafeArchiveExtractor` canonicalizes all target paths and rejects any entry escaping the destination root.
- **Command Allowlisting**: `Radare2Bridge` and `EngineConsoleRunner` invoke `ProcessBuilder` with explicit argument lists and allowlisted read-only queries without shell interpolation.
- **Local Key Custody**: Signing keys remain on-device in `AndroidKeyStore` or user-supplied PKCS#12 files.
