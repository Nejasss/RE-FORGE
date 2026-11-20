package org.bimz.reforge.engines

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class EngineRegistry(
    private val context: Context,
    private val nativeLoaded: Boolean,
    private val capstoneReportJson: String,
    private val radare2Bridge: Radare2Bridge
) {
    private val prefs = context.getSharedPreferences("reforge_engine_prefs", Context.MODE_PRIVATE)
    private val disabledIds = mutableSetOf<String>()
    private val testResults = mutableMapOf<String, String>()

    private val _engines = MutableStateFlow<List<EngineDescriptor>>(emptyList())
    val engines: StateFlow<List<EngineDescriptor>> = _engines.asStateFlow()

    init {
        val savedDisabled = prefs.getStringSet("disabled_engine_ids", emptySet()).orEmpty()
        disabledIds.addAll(savedDisabled)
        refresh()
    }

    fun setEngineEnabled(id: String, enabled: Boolean) {
        if (enabled) {
            disabledIds.remove(id)
        } else {
            disabledIds.add(id)
        }
        prefs.edit().putStringSet("disabled_engine_ids", disabledIds.toSet()).apply()
        refresh()
    }

    fun recordTestResult(id: String, result: String) {
        testResults[id] = result
        refresh()
    }

    fun isEngineEnabled(id: String): Boolean = !disabledIds.contains(id)

    fun refresh() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeLibSize = File(nativeDir, "libreforge_native.so").takeIf { it.exists() }?.length() ?: 0L
        val r2File = radare2Bridge.resolveBinary()
        val r2Available = r2File != null
        val capstoneCompiled = capstoneReportJson.contains("\"compiled\":true")

        val list = listOf(
            EngineDescriptor(
                id = "radare2_core",
                name = "Radare2 ARM64 Analysis Core",
                version = "5.9.8",
                sourceRepository = "https://github.com/radareorg/radare2",
                license = "LGPL-3.0-only",
                integrationType = EngineIntegrationType.BUNDLED_ARM64_EXECUTABLE,
                status = resolveStatus("radare2_core", if (r2Available) EngineStatus.INTEGRATED else EngineStatus.AVAILABLE),
                androidAbi = abi,
                initialized = r2Available,
                enabled = isEngineEnabled("radare2_core"),
                estimatedBinarySizeBytes = r2File?.length() ?: 0L,
                features = listOf("Full Auto Analysis (aaa)", "Function Disassembly (afl / pdf)", "Binary Info (ij / iSj / isj)", "Strings (izz)", "Hex & Seek (px / s)"),
                runtimeDependencies = listOf("jniLibs/arm64-v8a/libr2.so", "bionic libc"),
                compatibilityReport = if (r2Available) {
                    "Bundled ARM64 executable verified in nativeLibraryDir with W^X execute permission."
                } else {
                    "Cross-compiled in GitHub Actions via scripts/build-radare2-arm64.sh."
                },
                lastTestResult = testResults["radare2_core"] ?: if (r2Available) "Ready: ${r2File?.absolutePath}" else "NOT TESTED (binary absent)"
            ),
            EngineDescriptor(
                id = "native_elf_mmap",
                name = "Native ELF64/32, Mmap Hex & SHA-256 Engine",
                version = "1.0.0-native",
                sourceRepository = "org.bimz.reforge/native",
                license = "Apache-2.0",
                integrationType = EngineIntegrationType.JNI_CPP_NATIVE,
                status = resolveStatus("native_elf_mmap", if (nativeLoaded) EngineStatus.INTEGRATED else EngineStatus.FAILED),
                androidAbi = abi,
                initialized = nativeLoaded,
                enabled = isEngineEnabled("native_elf_mmap"),
                estimatedBinarySizeBytes = nativeLibSize,
                features = listOf("ELF64/ELF32 Headers", "Segments & Sections", "Symbols & Imports", "DT_NEEDED", "pread64 Hex Slice", "SHA-256 & CRC32"),
                runtimeDependencies = listOf("bionic libc", "liblog.so", "libreforge_native.so"),
                compatibilityReport = "Compiled with Android NDK C++20 and 16KB ELF page alignment (-Wl,-z,max-page-size=16384).",
                lastTestResult = testResults["native_elf_mmap"] ?: if (nativeLoaded) "JNI symbols resolved" else "Native library load failed"
            ),
            EngineDescriptor(
                id = "capstone_disasm",
                name = "Capstone Multi-Architecture Disassembler",
                version = "5.0.3",
                sourceRepository = "https://github.com/capstone-engine/capstone",
                license = "BSD-3-Clause",
                integrationType = EngineIntegrationType.JNI_CPP_NATIVE,
                status = resolveStatus(
                    "capstone_disasm",
                    when {
                        nativeLoaded && capstoneCompiled -> EngineStatus.INTEGRATED
                        nativeLoaded -> EngineStatus.PARTIALLY_INTEGRATED
                        else -> EngineStatus.FAILED
                    }
                ),
                androidAbi = abi,
                initialized = nativeLoaded && capstoneCompiled,
                enabled = isEngineEnabled("capstone_disasm"),
                estimatedBinarySizeBytes = nativeLibSize,
                features = listOf("ARM64 (AArch64)", "ARM32 / Thumb", "x86 / x86-64", "Dynamic cs_support() Probe"),
                runtimeDependencies = listOf("libreforge_native.so (statically linked libcapstone.a)"),
                compatibilityReport = if (capstoneCompiled) {
                    "Statically compiled into libreforge_native.so for ARM64: $capstoneReportJson"
                } else {
                    "Build compiled without CAPSTONE_SRC_DIR."
                },
                lastTestResult = testResults["capstone_disasm"] ?: capstoneReportJson
            )
        )
        _engines.value = list
    }

    private fun resolveStatus(id: String, activeStatus: EngineStatus): EngineStatus {
        return if (disabledIds.contains(id)) EngineStatus.DISABLED else activeStatus
    }
}
