package org.bimz.reforge.terminal

import android.content.Context

import org.bimz.reforge.engines.Radare2Bridge
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class InteractiveCommandOutcome(
    val entry: ConsoleEntry,
    val nextR2Mode: Boolean,
    val nextSeekHex: String,
    val requestSaveDump: Boolean = false
)

class ConsoleCommandRunner {

    private data class PersistentR2Session(
        val process: Process,
        val writer: BufferedWriter,
        val lines: LinkedBlockingQueue<String>,
        val targetPath: String,
        val alive: AtomicBoolean = AtomicBoolean(true)
    )

    private val sessions = ConcurrentHashMap<String, PersistentR2Session>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()

    fun closeSession(sessionId: String) {
        activeProcesses.remove(sessionId)?.let { proc ->
            runCatching { proc.destroyForcibly() }
        }
        sessions.remove(sessionId)?.let { sess ->
            sess.alive.set(false)
            runCatching {
                sess.writer.write("q!\n")
                sess.writer.flush()
            }
            runCatching { sess.process.destroyForcibly() }
        }
    }

    fun stopSession(sessionId: String) {
        activeProcesses.remove(sessionId)?.let { proc ->
            runCatching { proc.destroyForcibly() }
        }
        sessions.remove(sessionId)?.let { sess ->
            sess.alive.set(false)
            runCatching { sess.process.destroyForcibly() }
        }
    }

    fun executeInteractive(
        context: Context,
        rawInput: String,
        activeFile: File?,
        sessionId: String,
        currentR2Mode: Boolean,
        currentSeekHex: String,
        onProgress: (ConsoleEntry) -> Unit
    ): InteractiveCommandOutcome {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return InteractiveCommandOutcome(
                entry = ConsoleEntry(command = rawInput, output = "", isError = false, isR2Command = currentR2Mode),
                nextR2Mode = currentR2Mode,
                nextSeekHex = currentSeekHex
            )
        }

        if (trimmed.equals("save", ignoreCase = true) || trimmed.equals("export", ignoreCase = true)) {
            return InteractiveCommandOutcome(
                entry = ConsoleEntry(
                    command = trimmed,
                    output = "[RE FORGE] Saving latest output dump to Download/RE FORGE...",
                    isError = false,
                    isR2Command = currentR2Mode
                ),
                nextR2Mode = currentR2Mode,
                nextSeekHex = currentSeekHex,
                requestSaveDump = true
            )
        }

        if (currentR2Mode) {
            if (trimmed in listOf("q", "quit", "exit")) {
                closeSession(sessionId)
                return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = "[r2] Exited Radare2 interactive session.",
                        isError = false,
                        isR2Command = true
                    ),
                    nextR2Mode = false,
                    nextSeekHex = "0x00000000"
                )
            }
            if (activeFile == null || !activeFile.exists()) {
                closeSession(sessionId)
                return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = "[r2] No active target file loaded.",
                        isError = true,
                        isR2Command = true
                    ),
                    nextR2Mode = false,
                    nextSeekHex = currentSeekHex
                )
            }
            return runInPersistentR2(
                context = context,
                sessionId = sessionId,
                targetFile = activeFile,
                r2Command = trimmed,
                currentSeekHex = currentSeekHex,
                onProgress = onProgress
            )
        }

        // Shell mode commands
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase(Locale.US)
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        return when (cmd) {
            "help" -> InteractiveCommandOutcome(
                entry = ConsoleEntry(
                    command = trimmed,
                    output = buildString {
                        appendLine("RE FORGE Workstation Shell (ARM64)")
                        appendLine("  r2              - Enter persistent interactive Radare2 session")
                        appendLine("  r2 <cmd>        - Execute Radare2 command on active target")
                        appendLine("  elf             - Inspect ELF header & architecture")
                        appendLine("  hash            - Compute MD5 / SHA-1 / SHA-256")
                        appendLine("  engines         - Check Radare2 binary status & version")
                        appendLine("  save | export   - Save latest output to Download/RE FORGE")
                        appendLine("  clear           - Clear session terminal buffer")
                    }.trimEnd(),
                    isError = false
                ),
                nextR2Mode = false,
                nextSeekHex = currentSeekHex
            )

            "engines" -> {
                val bridge = Radare2Bridge(context)
                val bin = bridge.resolveBinary()
                val ver = runCatching { bridge.getVersion() }.getOrDefault("unknown")
                val status = if (bin != null && bin.exists()) {
                    "Radare2 Engine: READY\nBinary: ${bin.absolutePath}\nVersion: $ver"
                } else {
                    "Radare2 Engine: NOT FOUND in nativeLibraryDir / filesDir"
                }
                InteractiveCommandOutcome(
                    entry = ConsoleEntry(command = trimmed, output = status, isError = bin == null),
                    nextR2Mode = false,
                    nextSeekHex = currentSeekHex
                )
            }

            "elf" -> {
                if (activeFile == null || !activeFile.exists()) {
                    InteractiveCommandOutcome(
                        entry = ConsoleEntry(command = trimmed, output = "No active file selected.", isError = true),
                        nextR2Mode = false,
                        nextSeekHex = currentSeekHex
                    )
                } else {
                    InteractiveCommandOutcome(
                        entry = ConsoleEntry(command = trimmed, output = inspectElfHeader(activeFile), isError = false),
                        nextR2Mode = false,
                        nextSeekHex = currentSeekHex
                    )
                }
            }

            "hash" -> {
                if (activeFile == null || !activeFile.exists()) {
                    InteractiveCommandOutcome(
                        entry = ConsoleEntry(command = trimmed, output = "No active file selected.", isError = true),
                        nextR2Mode = false,
                        nextSeekHex = currentSeekHex
                    )
                } else {
                    InteractiveCommandOutcome(
                        entry = ConsoleEntry(command = trimmed, output = computeFileHashes(activeFile), isError = false),
                        nextR2Mode = false,
                        nextSeekHex = currentSeekHex
                    )
                }
            }

            "r2", "radare2" -> {
                if (activeFile == null || !activeFile.exists()) {
                    return InteractiveCommandOutcome(
                        entry = ConsoleEntry(
                            command = trimmed,
                            output = "[r2] Select a binary or file first before starting Radare2.",
                            isError = true
                        ),
                        nextR2Mode = false,
                        nextSeekHex = currentSeekHex
                    )
                }
                if (arg.isEmpty()) {
                    val initOutcome = runInPersistentR2(
                        context = context,
                        sessionId = sessionId,
                        targetFile = activeFile,
                        r2Command = "s",
                        currentSeekHex = currentSeekHex,
                        onProgress = onProgress
                    )
                    val seek = initOutcome.nextSeekHex
                    InteractiveCommandOutcome(
                        entry = initOutcome.entry.copy(
                            command = trimmed,
                            output = "[r2] Attached to ${activeFile.name} @ $seek (Persistent interactive mode • Unlimited analysis time).\nType 'aaa', 'afl', 'ij', 'pdf @ main', or 'exit' to leave r2.",
                            isR2Command = true
                        ),
                        nextR2Mode = !initOutcome.entry.isError,
                        nextSeekHex = seek
                    )
                } else {
                    val oneShot = runInPersistentR2(
                        context = context,
                        sessionId = sessionId,
                        targetFile = activeFile,
                        r2Command = arg,
                        currentSeekHex = currentSeekHex,
                        onProgress = onProgress
                    )
                    InteractiveCommandOutcome(
                        entry = oneShot.entry.copy(command = trimmed, isR2Command = true),
                        nextR2Mode = true,
                        nextSeekHex = oneShot.nextSeekHex
                    )
                }
            }

            else -> InteractiveCommandOutcome(
                entry = ConsoleEntry(
                    command = trimmed,
                    output = "Unknown command: '$cmd'. Type 'help' or 'r2' to start Radare2.",
                    isError = true
                ),
                nextR2Mode = false,
                nextSeekHex = currentSeekHex
            )
        }
    }

    private fun ensurePersistentSession(context: Context, sessionId: String, targetFile: File): Result<PersistentR2Session> {
        val existing = sessions[sessionId]
        if (existing != null && existing.alive.get() && existing.process.isAlive && existing.targetPath == targetFile.absolutePath) {
            return Result.success(existing)
        }
        closeSession(sessionId)

        val bridge = Radare2Bridge(context)
        val r2Bin = bridge.resolveBinary()
            ?: return Result.failure(IllegalStateException("Radare2 binary not found."))

        return runCatching {
            val pb = ProcessBuilder(
                r2Bin.absolutePath,
                "-e", "scr.color=0",
                "-e", "scr.utf8=0",
                "-e", "bin.relocs.apply=true",
                "-q",
                targetFile.absolutePath
            )
            pb.redirectErrorStream(true)
            val env = pb.environment()
            val libDir = r2Bin.parentFile?.absolutePath.orEmpty()
            val prevLd = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = if (prevLd.isBlank()) libDir else "$libDir:$prevLd"
            env["HOME"] = targetFile.parentFile?.absolutePath ?: "/data/local/tmp"

            val proc = pb.start()
            val queue = LinkedBlockingQueue<String>()
            val alive = AtomicBoolean(true)
            val readerThread = Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            queue.offer(line!!)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    alive.set(false)
                    queue.offer("__REFORGE_EOF__")
                }
            }, "r2-reader-$sessionId")
            readerThread.isDaemon = true
            readerThread.start()

            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            val sess = PersistentR2Session(
                process = proc,
                writer = writer,
                lines = queue,
                targetPath = targetFile.absolutePath,
                alive = alive
            )
            sessions[sessionId] = sess
            sess
        }
    }

    private fun runInPersistentR2(
        context: Context,
        sessionId: String,
        targetFile: File,
        r2Command: String,
        currentSeekHex: String,
        onProgress: (ConsoleEntry) -> Unit
    ): InteractiveCommandOutcome {
        val sessResult = ensurePersistentSession(context, sessionId, targetFile)
        val sess = sessResult.getOrElse { err ->
            return InteractiveCommandOutcome(
                entry = ConsoleEntry(
                    command = r2Command,
                    output = "[r2 error] ${err.message ?: "Failed to launch Radare2"}",
                    isError = true,
                    isR2Command = true
                ),
                nextR2Mode = false,
                nextSeekHex = currentSeekHex
            )
        }

        val marker = "__REFORGE_MARK_${System.nanoTime()}__"
        val baseEntry = ConsoleEntry(
            command = r2Command,
            output = "[r2] Executing '$r2Command'...",
            isError = false,
            isRunning = true,
            isR2Command = true
        )
        onProgress(baseEntry)

        val collected = StringBuilder()
        var detectedSeek = currentSeekHex
        var aborted = false

        try {
            synchronized(sess.writer) {
                sess.writer.write(r2Command)
                sess.writer.newLine()
                sess.writer.write("?e $marker")
                sess.writer.newLine()
                sess.writer.flush()
            }

            var lastUiPush = System.currentTimeMillis()
            while (true) {
                val line = sess.lines.poll(350, TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (!sess.process.isAlive || !sess.alive.get()) {
                        aborted = true
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUiPush >= 500L) {
                        val preview = if (collected.isEmpty()) "[r2] Analyzing '$r2Command' (unlimited time)..." else collected.toString()
                        onProgress(baseEntry.copy(output = preview, isRunning = true))
                        lastUiPush = now
                    }
                    continue
                }

                if (line == "__REFORGE_EOF__") {
                    aborted = true
                    break
                }

                if (line.contains(marker)) {
                    val beforeMarker = line.substringBefore(marker).trimEnd()
                    if (beforeMarker.isNotEmpty()) {
                        if (collected.isNotEmpty()) collected.append('\n')
                        collected.append(beforeMarker)
                    }
                    break
                }

                if (collected.isNotEmpty()) collected.append('\n')
                collected.append(line)

                val now = System.currentTimeMillis()
                if (now - lastUiPush >= 400L) {
                    onProgress(baseEntry.copy(output = collected.toString(), isRunning = true))
                    lastUiPush = now
                }
            }

            // Query current seek offset if session is still alive and command wasn't already 's'
            if (!aborted && sess.process.isAlive) {
                val seekOut = querySeekOffset(sess)
                if (seekOut != null) {
                    detectedSeek = seekOut
                } else if (r2Command.startsWith("s ")) {
                    val candidate = r2Command.removePrefix("s ").trim()
                    if (candidate.startsWith("0x", ignoreCase = true)) {
                        detectedSeek = candidate
                    }
                }
            }
        } catch (t: Throwable) {
            aborted = true
            if (collected.isNotEmpty()) collected.append('\n')
            collected.append("[r2 interrupted] ${t.message ?: "Process stopped"}")
        }

        if (aborted) {
            closeSession(sessionId)
        }

        val finalOutput = when {
            collected.isNotEmpty() -> collected.toString()
            aborted -> "[r2] Session stopped or terminated."
            else -> "(no output)"
        }

        return InteractiveCommandOutcome(
            entry = baseEntry.copy(
                output = finalOutput,
                isError = aborted,
                isRunning = false
            ),
            nextR2Mode = !aborted,
            nextSeekHex = detectedSeek
        )
    }

    private fun querySeekOffset(sess: PersistentR2Session): String? {
        return runCatching {
            val seekMarker = "__REFORGE_SEEK_${System.nanoTime()}__"
            synchronized(sess.writer) {
                sess.writer.write("s")
                sess.writer.newLine()
                sess.writer.write("?e $seekMarker")
                sess.writer.newLine()
                sess.writer.flush()
            }
            var found: String? = null
            while (true) {
                val line = sess.lines.poll(2, TimeUnit.SECONDS) ?: break
                if (line == "__REFORGE_EOF__") break
                if (line.contains(seekMarker)) {
                    val prefix = line.substringBefore(seekMarker).trim()
                    if (prefix.startsWith("0x", ignoreCase = true)) found = prefix
                    break
                }
                val trimmed = line.trim()
                if (trimmed.startsWith("0x", ignoreCase = true)) {
                    found = trimmed
                }
            }
            found
        }.getOrNull()
    }

    private fun inspectElfHeader(file: File): String {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 52L) return "File too small (${raf.length()} bytes) to be an ELF binary."
                val hdr = ByteArray(64)
                val read = raf.read(hdr)
                if (read < 52 || hdr[0] != 0x7F.toByte() || hdr[1] != 'E'.code.toByte() || hdr[2] != 'L'.code.toByte() || hdr[3] != 'F'.code.toByte()) {
                    return "Not an ELF binary (magic: ${hdr.take(4).joinToString(" ") { String.format("%02X", it) }})"
                }
                val eiClass = when (hdr[4].toInt() and 0xFF) {
                    1 -> "ELF32"
                    2 -> "ELF64"
                    else -> "Unknown"
                }
                val little = (hdr[5].toInt() and 0xFF) == 1
                fun u16(off: Int): Int = if (little) {
                    (hdr[off].toInt() and 0xFF) or ((hdr[off + 1].toInt() and 0xFF) shl 8)
                } else {
                    ((hdr[off].toInt() and 0xFF) shl 8) or (hdr[off + 1].toInt() and 0xFF)
                }
                fun u64(off: Int): Long {
                    var v = 0L
                    for (i in 0 until 8) {
                        val b = (hdr[off + i].toLong() and 0xFFL)
                        v = if (little) (v or (b shl (i * 8))) else ((v shl 8) or b)
                    }
                    return v
                }
                val eType = when (u16(16)) {
                    1 -> "REL (Relocatable)"
                    2 -> "EXEC (Executable)"
                    3 -> "DYN (Shared Object / PIE)"
                    4 -> "CORE"
                    else -> "0x${u16(16).toString(16)}"
                }
                val eMachine = when (u16(18)) {
                    0x03 -> "x86 (EM_386)"
                    0x28 -> "ARM32 (EM_ARM)"
                    0x3E -> "x86_64 (EM_X86_64)"
                    0xB7 -> "ARM64 / AArch64 (EM_AARCH64)"
                    0xF3 -> "RISC-V (EM_RISCV)"
                    else -> "0x${u16(18).toString(16)}"
                }
                val entry = if (eiClass == "ELF64" && read >= 32) {
                    "0x" + u64(24).toString(16)
                } else {
                    "0x" + ((u16(24).toLong() and 0xFFFFL) or ((u16(26).toLong() and 0xFFFFL) shl 16)).toString(16)
                }
                buildString {
                    appendLine("File: ${file.name} (${file.length()} bytes)")
                    appendLine("Format: $eiClass (${if (little) "Little Endian" else "Big Endian"})")
                    appendLine("Type: $eType")
                    appendLine("Arch: $eMachine")
                    appendLine("Entry Point: $entry")
                }.trimEnd()
            }
        }.getOrElse { "ELF parse error: ${it.message}" }
    }

    private fun computeFileHashes(file: File): String {
        return runCatching {
            val md5 = MessageDigest.getInstance("MD5")
            val sha1 = MessageDigest.getInstance("SHA-1")
            val sha256 = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md5.update(buf, 0, n)
                    sha1.update(buf, 0, n)
                    sha256.update(buf, 0, n)
                }
            }
            fun ByteArray.hex(): String = joinToString("") { String.format(Locale.US, "%02x", it) }
            buildString {
                appendLine("File: ${file.name}")
                appendLine("MD5:    ${md5.digest().hex()}")
                appendLine("SHA1:   ${sha1.digest().hex()}")
                appendLine("SHA256: ${sha256.digest().hex()}")
            }.trimEnd()
        }.getOrElse { "Hash error: ${it.message}" }
    }
}
