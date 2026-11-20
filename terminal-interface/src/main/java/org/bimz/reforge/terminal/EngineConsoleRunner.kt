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

/**
 * Application-specific console routing commands exclusively to verified RE FORGE engines.
 * Does not execute arbitrary system shells.
 */
class EngineConsoleRunner(
    private val engineRegistry: EngineRegistry,
    private val radare2Bridge: Radare2Bridge
) {
    suspend fun execute(rawInput: String, activeFile: File?): ConsoleEntry {
        val line = rawInput.trim()
        if (line.isEmpty()) {
            return ConsoleEntry(rawInput, "Enter 'help' to list integrated engine commands.", false)
        }

        val parts = line.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        return when (cmd) {
            "help" -> ConsoleEntry(
                line,
                """
                RE FORGE Engine Console (restricted to integrated engines)
                Commands:
                  engines               List registered engines and runtime status
                  hash                  Compute SHA-256, SHA-1, MD5, CRC32, and magic bytes of active file
                  elf                   Run native C++ ELF header/section/symbol summary on active file
                  capstone <arch> <off> Disassemble 64 bytes at hex offset (arch: arm64, arm, x86_64, x86)
                  r2 <query>            Run allowlisted Radare2 JSON/disasm query on active file (e.g. r2 ij)
                  clear                 Clear console output history
                """.trimIndent(),
                isError = false
            )

            "engines" -> {
                val summary = engineRegistry.engines.value.joinToString("\n") { e ->
                    "[${e.status}] ${e.name} (${e.version}) | ABI=${e.androidAbi} | init=${e.initialized}"
                }
                ConsoleEntry(line, summary, isError = false)
            }

            "hash" -> {
                val target = activeFile
                    ?: return ConsoleEntry(line, "No active workspace file selected.", isError = true)
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
                val target = activeFile
                    ?: return ConsoleEntry(line, "No active workspace file selected.", isError = true)
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
                val target = activeFile
                    ?: return ConsoleEntry(line, "No active workspace file selected.", isError = true)
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
                if (arg.isBlank()) {
                    return ConsoleEntry(line, "Usage: r2 <ij|iSj|isj|iij|izzj|aflj|pd 32>", isError = true)
                }
                val res = radare2Bridge.executeSafeQuery(activeFile, arg)
                val output = if (res.success) res.stdout.ifBlank { "(empty output)" } else res.stderr
                ConsoleEntry(line, output, isError = !res.success)
            }

            else -> ConsoleEntry(
                line,
                "Unknown engine command '$cmd'. Type 'help' for supported commands.",
                isError = true
            )
        }
    }
}
