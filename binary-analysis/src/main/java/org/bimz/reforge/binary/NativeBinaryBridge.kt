package org.bimz.reforge.binary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.BinaryStringModel
import org.bimz.reforge.core.model.DisassembledInstruction
import org.bimz.reforge.core.model.ElfBinaryReport
import org.bimz.reforge.core.model.ElfSectionModel
import org.bimz.reforge.core.model.ElfSegmentModel
import org.bimz.reforge.core.model.ElfSymbolModel
import org.json.JSONObject
import java.io.File

object NativeBinaryBridge {

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("reforge_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun nativeAnalyzeElf(filePath: String, maxSymbols: Int, maxStrings: Int): String
    external fun nativeComputeHashes(filePath: String): String
    external fun nativeReadSlice(filePath: String, offset: Long, length: Int): ByteArray
    external fun nativeSearchBytes(filePath: String, startOffset: Long, pattern: ByteArray): Long
    external fun nativePatchBytes(filePath: String, offset: Long, replacement: ByteArray): Boolean
    external fun nativeCapstoneCapabilities(): String
    external fun nativeDisassemble(
        arch: String,
        mode: String,
        code: ByteArray,
        baseAddress: Long,
        maxInstructions: Int
    ): String

    fun queryCapstoneCapabilities(): String {
        if (!isNativeLoaded) return "{\"compiled\":false,\"error\":\"libreforge_native.so not loaded\"}"
        return nativeCapstoneCapabilities()
    }

    suspend fun analyzeElf(file: File): Result<ElfBinaryReport> = withContext(Dispatchers.IO) {
        runCatching {
            check(isNativeLoaded) { "Native JNI library libreforge_native.so is not loaded" }
            val rawJson = nativeAnalyzeElf(file.absolutePath, 4096, 2048)
            val root = JSONObject(rawJson)
            val valid = root.optBoolean("valid", false)
            if (!valid) {
                return@runCatching ElfBinaryReport(
                    valid = false,
                    error = root.optString("error", "Invalid ELF binary"),
                    bitWidth = 0,
                    littleEndian = true,
                    objectType = 0,
                    machine = 0,
                    architectureName = "Unknown",
                    entryPoint = 0L,
                    segments = emptyList(),
                    sections = emptyList(),
                    symbols = emptyList(),
                    dependencies = emptyList(),
                    strings = emptyList()
                )
            }

            val segmentsArr = root.getJSONArray("segments")
            val segments = (0 until segmentsArr.length()).map { i ->
                val o = segmentsArr.getJSONObject(i)
                val t = o.getInt("type")
                ElfSegmentModel(
                    index = o.getInt("index"),
                    type = t,
                    typeName = segmentTypeName(t),
                    flags = o.getInt("flags"),
                    fileOffset = o.getLong("fileOffset"),
                    virtualAddress = o.getLong("virtualAddress"),
                    fileSize = o.getLong("fileSize"),
                    memSize = o.getLong("memSize"),
                    alignment = o.getLong("alignment")
                )
            }

            val sectionsArr = root.getJSONArray("sections")
            val sections = (0 until sectionsArr.length()).map { i ->
                val o = sectionsArr.getJSONObject(i)
                ElfSectionModel(
                    index = o.getInt("index"),
                    name = o.getString("name"),
                    type = o.getInt("type"),
                    flags = o.getLong("flags"),
                    virtualAddress = o.getLong("virtualAddress"),
                    fileOffset = o.getLong("fileOffset"),
                    size = o.getLong("size"),
                    alignment = o.getLong("alignment")
                )
            }

            val symbolsArr = root.getJSONArray("symbols")
            val symbols = (0 until symbolsArr.length()).map { i ->
                val o = symbolsArr.getJSONObject(i)
                ElfSymbolModel(
                    name = o.getString("name"),
                    value = o.getLong("value"),
                    size = o.getLong("size"),
                    bind = o.getInt("bind"),
                    type = o.getInt("type"),
                    isDynamic = o.getBoolean("isDynamic"),
                    isImport = o.getBoolean("isImport")
                )
            }

            val depsArr = root.getJSONArray("dependencies")
            val deps = (0 until depsArr.length()).map { i -> depsArr.getString(i) }

            val stringsArr = root.getJSONArray("strings")
            val strings = (0 until stringsArr.length()).map { i ->
                val o = stringsArr.getJSONObject(i)
                BinaryStringModel(
                    offset = o.getLong("offset"),
                    section = o.getString("section"),
                    value = o.getString("value")
                )
            }

            ElfBinaryReport(
                valid = true,
                error = "",
                bitWidth = root.getInt("bitWidth"),
                littleEndian = root.getBoolean("littleEndian"),
                objectType = root.getInt("objectType"),
                machine = root.getInt("machine"),
                architectureName = root.getString("architectureName"),
                entryPoint = root.getLong("entryPoint"),
                segments = segments,
                sections = sections,
                symbols = symbols,
                dependencies = deps,
                strings = strings
            )
        }
    }

    suspend fun disassembleSlice(
        file: File,
        arch: String,
        mode: String,
        fileOffset: Long,
        byteLength: Int,
        virtualBaseAddress: Long,
        maxInstructions: Int = 128
    ): Result<List<DisassembledInstruction>> = withContext(Dispatchers.IO) {
        runCatching {
            check(isNativeLoaded) { "Native JNI library libreforge_native.so is not loaded" }
            val bytes = nativeReadSlice(file.absolutePath, fileOffset, byteLength.coerceIn(1, 32768))
            if (bytes.isEmpty()) {
                throw IllegalArgumentException("No bytes read at offset 0x${fileOffset.toString(16)}")
            }
            val json = nativeDisassemble(arch, mode, bytes, virtualBaseAddress, maxInstructions)
            val root = JSONObject(json)
            if (!root.optBoolean("ok", false)) {
                throw IllegalStateException(root.optString("error", "Capstone disassembly failed"))
            }
            val arr = root.getJSONArray("instructions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DisassembledInstruction(
                    address = o.getLong("address"),
                    size = o.getInt("size"),
                    bytes = o.getString("bytes"),
                    mnemonic = o.getString("mnemonic"),
                    opStr = o.getString("opStr")
                )
            }
        }
    }

    private fun segmentTypeName(type: Int): String = when (type) {
        0 -> "PT_NULL"
        1 -> "PT_LOAD"
        2 -> "PT_DYNAMIC"
        3 -> "PT_INTERP"
        4 -> "PT_NOTE"
        6 -> "PT_PHDR"
        7 -> "PT_TLS"
        0x6474e550 -> "PT_GNU_EH_FRAME"
        0x6474e551 -> "PT_GNU_STACK"
        0x6474e552 -> "PT_GNU_RELRO"
        else -> "0x${type.toUInt().toString(16)}"
    }
}
