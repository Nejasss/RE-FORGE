package org.bimz.reforge.terminal

import org.bimz.reforge.binary.NativeBinaryBridge
import org.bimz.reforge.core.security.HashCalculator
import org.bimz.reforge.engines.EngineRegistry
import org.bimz.reforge.engines.Radare2Bridge
import java.io.File

data class ConsoleEntry(
    val command: String,
    val output: String,
    val isError: Boolean,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

class EngineConsoleRunner(
    private val engineRegistry: EngineRegistry,
    private val radare2Bridge: Radare2Bridge
) {
    suspend fun execute(rawInput: String, activeFile: File?): ConsoleEntry {
        val line = rawInput.trim()
        if (line.isEmpty()) {
            return ConsoleEntry(rawInput, "Type 'help' for available commands.", false)
        }

        val parts = line.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        return when (cmd) {
            "help" -> ConsoleEntry(
                line,
                """
                RE FORGE Workstation Shell
                  help                  Show available commands
                  engines               List all engines and ON/OFF status
                  hash                  Compute SHA-256, SHA-1, MD5, CRC32 of active target
                  elf                   Parse ELF64/32 headers, sections, and symbols
                  capstone <arch> <off> Disassemble 64 bytes (arm64, arm, x86_64, x86)
                  r2 <cmd>              Execute any Radare2 command (e.g. r2 aaa; afl, r2 ij, r2 pdf @ main)
                  clear                 Clear terminal buffer
                """.trimIndent(),
                isError = false
            )

            "engines" -> {
                val summary = engineRegistry.engines.value.joinToString("\n") { e ->
                    "[${e.status}] ${e.name} (${e.version}) | enabled=${e.enabled}"
                }
                ConsoleEntry(line, summary, isError = false)
            }

            "hash" -> {
                val target = activeFile
                    ?: return ConsoleEntry(line, "No active workspace file loaded. Open a target from Home first.", isError = true)
                val report = HashCalculator.computeReport(target)
                val text = """
                File   : ${report.fileName} (${report.fileSize} bytes)
                Format : ${report.signatureLabel}
                Magic  : ${report.magicHex}
                SHA256 : ${report.sha256}
                SHA1   : ${report.sha1}
                MD5    : ${report.md5}
                CRC32  : ${report.crc32}
                """.trimIndent()
                ConsoleEntry(line, text, isError = false)
            }

            "elf" -> {
                if (!engineRegistry.isEngineEnabled("native_elf_mmap")) {
                    return ConsoleEntry(line, "Native ELF engine is OFF. Enable it in the Engines tab.", isError = true)
                }
                val target = activeFile
                    ?: return ConsoleEntry(line, "No active workspace file loaded.", isError = true)
                val res = NativeBinaryBridge.analyzeElf(target)
                res.fold(
                    onSuccess = { report ->
                        if (!report.valid) {
                            ConsoleEntry(line, "ELF parser: ${report.error}", isError = true)
                        } else {
                            val text = """
                            Arch       : ${report.architectureName} (${report.bitWidth}-bit, ${if (report.littleEndian) "LE" else "BE"})
                            EntryPoint : 0x${report.entryPoint.toString(16)}
                            Segments   : ${report.segments.size}
                            Sections   : ${report.sections.size}
                            Symbols    : ${report.symbols.size}
                            Deps       : ${report.dependencies.joinToString(", ").ifEmpty { "none" }}
                            """.trimIndent()
                            ConsoleEntry(line, text, isError = false)
                        }
                    },
                    onFailure = { ConsoleEntry(line, "ELF inspection error: ${it.message}", isError = true) }
                )
            }

            "capstone" -> {
                if (!engineRegistry.isEngineEnabled("capstone_disasm")) {
                    return ConsoleEntry(line, "Capstone engine is OFF. Enable it in the Engines tab.", isError = true)
                }
                val target = activeFile
                    ?: return ConsoleEntry(line, "No active workspace file loaded.", isError = true)
                val tokens = arg.split(Regex("\\s+"))
                val arch = tokens.getOrNull(0)?.lowercase().orEmpty()
                val offset = tokens.getOrNull(1)?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
                if (arch.isBlank()) {
                    return ConsoleEntry(line, "Usage: capstone <arm64|arm|x86_64|x86> <hex_offset>", isError = true)
                }
                val res = NativeBinaryBridge.disassembleSlice(target, arch, "arm", offset, 64, offset, 32)
                res.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) {
                            ConsoleEntry(line, "Capstone decoded 0 instructions at 0x${offset.toString(16)}.", isError = true)
                        } else {
                            val out = list.joinToString("\n") { ins ->
                                "0x${ins.address.toString(16).padStart(8, '0')}  ${ins.bytes.padEnd(14)}  ${ins.mnemonic} ${ins.opStr}"
                            }
                            ConsoleEntry(line, out, isError = false)
                        }
                    },
                    onFailure = { ConsoleEntry(line, "Capstone error: ${it.message}", isError = true) }
                )
            }

            "r2" -> {
                if (!engineRegistry.isEngineEnabled("radare2_core")) {
                    return ConsoleEntry(line, "Radare2 engine is OFF. Enable it in the Engines tab.", isError = true)
                }
                if (arg.isBlank()) {
                    return ConsoleEntry(line, "Usage: r2 <command> (e.g. r2 aaa; afl, r2 ij, r2 pdf @ main)", isError = true)
                }
                val res = radare2Bridge.executeCommand(activeFile, arg)
                val combined = buildString {
                    if (res.stdout.isNotBlank()) append(res.stdout.trimEnd())
                    if (res.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(res.stderr.trimEnd())
                    }
                }.ifBlank { "(exit ${res.exitCode}, no output)" }
                ConsoleEntry(line, combined, isError = !res.success)
            }

            else -> ConsoleEntry(
                line,
                "sh: $cmd: command not found. Type 'help' or prefix Radare2 commands with 'r2 '.",
                isError = true
            )
        }
    }
}
