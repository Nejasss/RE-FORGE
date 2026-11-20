package org.bimz.reforge.terminal

import org.bimz.reforge.binary.NativeBinaryBridge
import org.bimz.reforge.core.security.HashCalculator
import org.bimz.reforge.engines.EngineRegistry
import org.bimz.reforge.engines.Radare2Bridge
import java.io.File
import java.util.Locale

data class ConsoleEntry(
    val command: String,
    val output: String,
    val isError: Boolean,
    val isRunning: Boolean = false,
    val isR2Command: Boolean = false,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val id: Long = System.nanoTime()
)

data class ConsoleTurnOutcome(
    val entry: ConsoleEntry,
    val nextR2Mode: Boolean,
    val requestSaveDump: Boolean = false
)

class EngineConsoleRunner(
    private val engineRegistry: EngineRegistry,
    private val radare2Bridge: Radare2Bridge
) {
    suspend fun execute(rawInput: String, activeFile: File?): ConsoleEntry {
        return executeInteractive(rawInput, activeFile, currentR2Mode = false, onUpdate = null).entry
    }

    suspend fun executeInteractive(
        rawInput: String,
        activeFile: File?,
        currentR2Mode: Boolean,
        onUpdate: ((ConsoleEntry) -> Unit)?
    ): ConsoleTurnOutcome {
        val line = rawInput.trim()
        if (line.isEmpty()) {
            return ConsoleTurnOutcome(
                entry = ConsoleEntry(
                    command = rawInput,
                    output = if (currentR2Mode) "r2 interactive mode active. Type a command (e.g. aaa, afl, ij) or 'exit'." else "Type 'help' or 'r2' to enter Radare2 mode.",
                    isError = false,
                    isR2Command = currentR2Mode
                ),
                nextR2Mode = currentR2Mode
            )
        }

        val parts = line.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        if (cmd == "save" || cmd == "export") {
            return ConsoleTurnOutcome(
                entry = ConsoleEntry(
                    command = line,
                    output = "Opening Save Dump dialog for latest terminal output...",
                    isError = false,
                    isR2Command = currentR2Mode
                ),
                nextR2Mode = currentR2Mode,
                requestSaveDump = true
            )
        }

        if (currentR2Mode) {
            if (cmd == "exit" || cmd == "q" || cmd == "quit") {
                return ConsoleTurnOutcome(
                    entry = ConsoleEntry(
                        command = line,
                        output = "[r2] Exited Radare2 interactive session. Returned to workstation shell.",
                        isError = false,
                        isR2Command = true
                    ),
                    nextR2Mode = false
                )
            }
            if (cmd == "help") {
                return ConsoleTurnOutcome(
                    entry = ConsoleEntry(
                        command = line,
                        output = """
                        Radare2 Interactive Session Commands (no 'r2 ' prefix needed):
                          aaa / AA          Full / All auto-analysis with live progress
                          afl               List all discovered functions
                          pdf @ <sym|addr>  Disassemble function (e.g. pdf @ main)
                          ij / iSj / isj    Binary info, sections, symbols (JSON)
                          izz               Extract all strings across binary
                          px 64             Hexdump 64 bytes at current seek
                          save              Save latest dump output to device storage
                          clear             Clear terminal buffer
                          exit / q          Exit Radare2 mode back to shell
                        """.trimIndent(),
                        isError = false,
                        isR2Command = true
                    ),
                    nextR2Mode = true
                )
            }

            val r2Query = if (cmd == "r2" && arg.isNotBlank()) arg else line
            val r2Entry = runR2WithProgress(
                displayCommand = r2Query,
                r2Query = r2Query,
                activeFile = activeFile,
                onUpdate = onUpdate
            )
            return ConsoleTurnOutcome(entry = r2Entry, nextR2Mode = true)
        }

        return when (cmd) {
            "help" -> ConsoleTurnOutcome(
                entry = ConsoleEntry(
                    command = line,
                    output = """
                    RE FORGE Workstation Shell
                      r2                    Enter sticky Radare2 interactive mode (r2 [0x00000000]>)
                      r2 <cmd>              Run Radare2 command & stay in r2 mode (e.g. r2 aaa)
                      elf                   Parse ELF64/32 headers, sections, and symbols
                      capstone <arch> <off> Disassemble 64 bytes (arm64, arm, x86_64, x86)
                      hash                  Compute SHA-256, SHA-1, MD5, CRC32 of active target
                      engines               List all engines and ON/OFF status
                      save                  Save latest dump output to device storage
                      clear                 Clear terminal buffer
                    """.trimIndent(),
                    isError = false
                ),
                nextR2Mode = false
            )

            "r2" -> {
                if (!engineRegistry.isEngineEnabled("radare2_core")) {
                    return ConsoleTurnOutcome(
                        entry = ConsoleEntry(line, "Radare2 engine is OFF. Enable it in the Engines tab.", isError = true),
                        nextR2Mode = false
                    )
                }
                if (arg.isBlank()) {
                    val targetName = activeFile?.name ?: "none (load target from Home)"
                    return ConsoleTurnOutcome(
                        entry = ConsoleEntry(
                            command = line,
                            output = "[r2] Entered Radare2 interactive mode.\n[r2] Active target: $targetName\n[r2] Type commands directly (e.g. aaa, AA, afl, ij, pdf @ main) or 'exit' to leave.",
                            isError = false,
                            isR2Command = true
                        ),
                        nextR2Mode = true
                    )
                }
                val r2Entry = runR2WithProgress(
                    displayCommand = line,
                    r2Query = arg,
                    activeFile = activeFile,
                    onUpdate = onUpdate
                )
                ConsoleTurnOutcome(entry = r2Entry, nextR2Mode = true)
            }

            "engines" -> {
                val summary = engineRegistry.engines.value.joinToString("\n") { e ->
                    "[${e.status}] ${e.name} (${e.version}) | enabled=${e.enabled}"
                }
                ConsoleTurnOutcome(ConsoleEntry(line, summary, isError = false), nextR2Mode = false)
            }

            "hash" -> {
                val target = activeFile
                    ?: return ConsoleTurnOutcome(ConsoleEntry(line, "No active workspace file loaded. Open a target from Home first.", isError = true), false)
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
                ConsoleTurnOutcome(ConsoleEntry(line, text, isError = false), false)
            }

            "elf" -> {
                if (!engineRegistry.isEngineEnabled("native_elf_mmap")) {
                    return ConsoleTurnOutcome(ConsoleEntry(line, "Native ELF engine is OFF. Enable it in the Engines tab.", isError = true), false)
                }
                val target = activeFile
                    ?: return ConsoleTurnOutcome(ConsoleEntry(line, "No active workspace file loaded.", isError = true), false)
                val res = NativeBinaryBridge.analyzeElf(target)
                val entry = res.fold(
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
                ConsoleTurnOutcome(entry, false)
            }

            "capstone" -> {
                if (!engineRegistry.isEngineEnabled("capstone_disasm")) {
                    return ConsoleTurnOutcome(ConsoleEntry(line, "Capstone engine is OFF. Enable it in the Engines tab.", isError = true), false)
                }
                val target = activeFile
                    ?: return ConsoleTurnOutcome(ConsoleEntry(line, "No active workspace file loaded.", isError = true), false)
                val tokens = arg.split(Regex("\\s+"))
                val arch = tokens.getOrNull(0)?.lowercase().orEmpty()
                val offset = tokens.getOrNull(1)?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
                if (arch.isBlank()) {
                    return ConsoleTurnOutcome(ConsoleEntry(line, "Usage: capstone <arm64|arm|x86_64|x86> <hex_offset>", isError = true), false)
                }
                val res = NativeBinaryBridge.disassembleSlice(target, arch, "arm", offset, 64, offset, 32)
                val entry = res.fold(
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
                ConsoleTurnOutcome(entry, false)
            }

            else -> ConsoleTurnOutcome(
                entry = ConsoleEntry(
                    command = line,
                    output = "sh: $cmd: command not found. Type 'r2' to enter Radare2 mode or 'help' for shell commands.",
                    isError = true
                ),
                nextR2Mode = false
            )
        }
    }

    private suspend fun runR2WithProgress(
        displayCommand: String,
        r2Query: String,
        activeFile: File?,
        onUpdate: ((ConsoleEntry) -> Unit)?
    ): ConsoleEntry {
        if (!engineRegistry.isEngineEnabled("radare2_core")) {
            return ConsoleEntry(displayCommand, "Radare2 engine is OFF. Enable it in the Engines tab.", isError = true, isR2Command = true)
        }
        val entryId = System.nanoTime()
        val targetLabel = activeFile?.name ?: "no-file"
        val isAnalysis = r2Query.split(';', ' ').any {
            val t = it.trim()
            t == "aa" || t == "aaa" || t == "aaaa" || t == "AA" || t == "aac"
        }

        val startBanner = if (isAnalysis) {
            "[r2] Starting deep binary analysis ($r2Query) on '$targetLabel'...\n[r2] Please wait, analyzing functions, xrefs & strings..."
        } else {
            "[r2] Executing '$r2Query' on '$targetLabel'..."
        }

        val liveLines = mutableListOf<String>()
        var latestTicker = ""

        onUpdate?.invoke(
            ConsoleEntry(
                command = displayCommand,
                output = startBanner,
                isError = false,
                isRunning = true,
                isR2Command = true,
                id = entryId
            )
        )

        val res = radare2Bridge.executeCommandStreaming(activeFile, r2Query) { progressLine ->
            if (progressLine.startsWith("[r2:progress]")) {
                latestTicker = progressLine
            } else if (progressLine.isNotBlank()) {
                synchronized(liveLines) {
                    liveLines.add(progressLine)
                    if (liveLines.size > 40) {
                        liveLines.removeAt(0)
                    }
                }
            }
            val snapshot = buildString {
                appendLine(startBanner)
                synchronized(liveLines) {
                    liveLines.takeLast(12).forEach { appendLine(it) }
                }
                if (latestTicker.isNotBlank()) {
                    append(latestTicker)
                }
            }.trimEnd()

            onUpdate?.invoke(
                ConsoleEntry(
                    command = displayCommand,
                    output = snapshot,
                    isError = false,
                    isRunning = true,
                    isR2Command = true,
                    id = entryId
                )
            )
        }

        val elapsedSec = String.format(Locale.US, "%.2fs", res.durationMs / 1000.0)
        val statusFooter = if (res.success) {
            if (isAnalysis) {
                "[r2] Analysis ($r2Query) COMPLETED in $elapsedSec. Ready for next command (e.g. afl, pdf @ main, izz)."
            } else {
                "[r2] Done in $elapsedSec (exit 0)."
            }
        } else {
            "[r2] Failed after $elapsedSec (exit ${res.exitCode})."
        }

        val finalOutput = buildString {
            if (isAnalysis) {
                appendLine("[r2] Analysis ($r2Query) started on '$targetLabel'...")
            }
            if (res.stdout.isNotBlank()) {
                appendLine(res.stdout.trimEnd())
            }
            if (res.stderr.isNotBlank()) {
                appendLine(res.stderr.trimEnd())
            }
            append(statusFooter)
        }.trim()

        return ConsoleEntry(
            command = displayCommand,
            output = finalOutput,
            isError = !res.success,
            isRunning = false,
            isR2Command = true,
            id = entryId
        )
    }
}
