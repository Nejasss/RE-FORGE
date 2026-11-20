package org.bimz.reforge.core.model

enum class FileFormat(
    val label: String,
    val category: String,
    val supportsApkInspection: Boolean = false,
    val supportsDexAnalysis: Boolean = false,
    val supportsElfAnalysis: Boolean = false,
    val supportsTextEdit: Boolean = false
) {
    APK("Android Package (APK)", "Android", supportsApkInspection = true, supportsDexAnalysis = true),
    APKS("Split APK Archive (APKS)", "Android", supportsApkInspection = true),
    AAB("Android App Bundle (AAB)", "Android", supportsApkInspection = true),
    DEX("Dalvik Executable (DEX)", "Android", supportsDexAnalysis = true),
    JAR("Java Archive (JAR)", "JVM"),
    ZIP("ZIP Archive", "Archive"),
    ELF("ELF Executable / Shared Object", "Binary", supportsElfAnalysis = true),
    PE("Windows PE Executable", "Binary"),
    MACHO("Mach-O Binary", "Binary"),
    WASM("WebAssembly Module", "Binary"),
    JAVA_CLASS("Java Class Bytecode", "JVM"),
    AXML("Android Binary XML", "Resource"),
    XML("Text XML Document", "Resource", supportsTextEdit = true),
    JSON("JSON Document", "Text", supportsTextEdit = true),
    SMALI("Smali Assembly Source", "Android", supportsTextEdit = true),
    RAW_BINARY("Raw Binary File", "Binary");

    companion object {
        fun detect(fileName: String, headerBytes: ByteArray): FileFormat {
            val lower = fileName.lowercase()
            if (headerBytes.size >= 4) {
                if (headerBytes[0] == 0x7f.toByte() &&
                    headerBytes[1] == 'E'.code.toByte() &&
                    headerBytes[2] == 'L'.code.toByte() &&
                    headerBytes[3] == 'F'.code.toByte()
                ) {
                    return ELF
                }
                if (headerBytes[0] == 'd'.code.toByte() &&
                    headerBytes[1] == 'e'.code.toByte() &&
                    headerBytes[2] == 'x'.code.toByte() &&
                    headerBytes[3] == 0x0a.toByte()
                ) {
                    return DEX
                }
                if (headerBytes[0] == 0x03.toByte() &&
                    headerBytes[1] == 0x00.toByte() &&
                    headerBytes[2] == 0x08.toByte() &&
                    headerBytes[3] == 0x00.toByte()
                ) {
                    return AXML
                }
                if (headerBytes[0] == 0x00.toByte() &&
                    headerBytes[1] == 'a'.code.toByte() &&
                    headerBytes[2] == 's'.code.toByte() &&
                    headerBytes[3] == 'm'.code.toByte()
                ) {
                    return WASM
                }
                if (headerBytes[0] == 0xca.toByte() &&
                    headerBytes[1] == 0xfe.toByte() &&
                    headerBytes[2] == 0xba.toByte() &&
                    headerBytes[3] == 0xbe.toByte()
                ) {
                    return JAVA_CLASS
                }
                if (headerBytes[0] == 'P'.code.toByte() &&
                    headerBytes[1] == 'K'.code.toByte() &&
                    headerBytes[2] == 0x03.toByte() &&
                    headerBytes[3] == 0x04.toByte()
                ) {
                    return when {
                        lower.endsWith(".apk") -> APK
                        lower.endsWith(".apks") || lower.endsWith(".xapk") -> APKS
                        lower.endsWith(".aab") -> AAB
                        lower.endsWith(".jar") -> JAR
                        else -> ZIP
                    }
                }
            }
            if (headerBytes.size >= 2 &&
                headerBytes[0] == 'M'.code.toByte() &&
                headerBytes[1] == 'Z'.code.toByte()
            ) {
                return PE
            }
            return when {
                lower.endsWith(".smali") -> SMALI
                lower.endsWith(".xml") -> XML
                lower.endsWith(".json") -> JSON
                lower.endsWith(".apk") -> APK
                lower.endsWith(".dex") -> DEX
                lower.endsWith(".so") || lower.endsWith(".elf") -> ELF
                else -> RAW_BINARY
            }
        }
    }
}
