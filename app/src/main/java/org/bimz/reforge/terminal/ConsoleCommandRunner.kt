package org.bimz.reforge.terminal

import android.content.Context

import org.bimz.reforge.engines.Radare2Bridge
import org.bimz.reforge.storage.SavedForgeFile
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class InteractiveCommandOutcome(
    val entry: ConsoleEntry,
    val nextR2Mode: Boolean,
    val nextSeekHex: String,
    val requestSaveDump: Boolean = false,
    val createdDumpFile: SavedForgeFile? = null,
    val updatedTargetFile: File? = null
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
    private val cancelledSessions = ConcurrentHashMap.newKeySet<String>()

    private fun formatTargetSize(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun closeSession(sessionId: String) {
        cancelledSessions.add(sessionId)
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
        cancelledSessions.add(sessionId)
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
        availableTargets: List<File> = emptyList(),
        sessionId: String,
        currentR2Mode: Boolean,
        currentSeekHex: String,
        onProgress: (ConsoleEntry) -> Unit
    ): InteractiveCommandOutcome {
        cancelledSessions.remove(sessionId)
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return InteractiveCommandOutcome(
                entry = ConsoleEntry(command = rawInput, output = "", isError = false, isR2Command = currentR2Mode),
                nextR2Mode = currentR2Mode,
                nextSeekHex = currentSeekHex
            )
        }

        if (
            trimmed.equals("target", ignoreCase = true) ||
            trimmed.equals("targets", ignoreCase = true) ||
            trimmed.startsWith("target ", ignoreCase = true)
        ) {
            val arg = trimmed.substringAfter(" ", missingDelimiterValue = "").trim()
            val uniqueFiles = LinkedHashMap<String, File>()
            if (activeFile != null && activeFile.exists()) {
                uniqueFiles[activeFile.absolutePath] = activeFile
            }
            availableTargets.filter { it.exists() }.forEach { f ->
                uniqueFiles.putIfAbsent(f.absolutePath, f)
            }
            val candidates = uniqueFiles.values.toList()
            if (candidates.isEmpty()) {
                return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = "[target] No binaries imported yet. Import a binary from Home or Vault first.",
                        isError = true,
                        isR2Command = currentR2Mode
                    ),
                    nextR2Mode = currentR2Mode,
                    nextSeekHex = currentSeekHex
                )
            }
            if (arg.isEmpty()) {
                val listText = buildString {
                    appendLine("Available Binaries (${candidates.size}):")
                    candidates.forEachIndexed { idx, f ->
                        val activeTag = if (f.absolutePath == activeFile?.absolutePath) " [ACTIVE]" else ""
                        val sha = runCatching { ForgeDumpGenerator.sha256File(f) }.getOrNull()
                        val cacheTag = if (sha != null && ForgeDumpGenerator.isAaaCached(context, sha)) " [aaa-cached]" else ""
                        appendLine("  [${idx + 1}] ${f.name} (${formatTargetSize(f.length())})$activeTag$cacheTag")
                    }
                    append("Usage: target <number|name>  (e.g. target 1)")
                }
                return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = listText,
                        isError = false,
                        isR2Command = currentR2Mode
                    ),
                    nextR2Mode = currentR2Mode,
                    nextSeekHex = currentSeekHex
                )
            }

            val chosen = arg.toIntOrNull()?.let { idx -> candidates.getOrNull(idx - 1) }
                ?: candidates.firstOrNull { it.name.equals(arg, ignoreCase = true) }
                ?: candidates.firstOrNull { it.name.contains(arg, ignoreCase = true) }

            if (chosen == null) {
                return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = "[target] Target '$arg' not found. Run 'target' to list available binaries.",
                        isError = true,
                        isR2Command = currentR2Mode
                    ),
                    nextR2Mode = currentR2Mode,
                    nextSeekHex = currentSeekHex
                )
            }

            if (currentR2Mode) {
                closeSession(sessionId)
            }
            val sha = runCatching { ForgeDumpGenerator.sha256File(chosen) }.getOrNull()
            val cachedNote = if (sha != null && ForgeDumpGenerator.isAaaCached(context, sha)) {
                " (aaa analysis cached)"
            } else {
                ""
            }
            return InteractiveCommandOutcome(
                entry = ConsoleEntry(
                    command = trimmed,
                    output = "[target] Switched active target to ${chosen.name} (${formatTargetSize(chosen.length())})$cachedNote.",
                    isError = false,
                    isR2Command = false
                ),
                nextR2Mode = false,
                nextSeekHex = "0x00000000",
                updatedTargetFile = chosen
            )
        }

        if (trimmed.equals("dump", ignoreCase = true) || trimmed.startsWith("dump ", ignoreCase = true)) {
            if (activeFile == null || !activeFile.exists()) {
                return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = "[dump] Select a binary first ('target' or import from Home).",
                        isError = true,
                        isR2Command = currentR2Mode
                    ),
                    nextR2Mode = currentR2Mode,
                    nextSeekHex = currentSeekHex
                )
            }
            val rawArg = trimmed.removePrefix("dump").removePrefix("DUMP").trim()
            val parsed = ForgeDumpGenerator.parseArgs(rawArg)
                ?: return InteractiveCommandOutcome(
                    entry = ConsoleEntry(
                        command = trimmed,
                        output = "Usage: dump [full|exports|imports|functions|strings|sections] [txt|md|json] [force]",
                        isError = true,
                        isR2Command = currentR2Mode
                    ),
                    nextR2Mode = currentR2Mode,
                    nextSeekHex = currentSeekHex
                )
            val baseEntry = ConsoleEntry(
                command = trimmed,
                output = "[dump] Initializing r2dump (${parsed.mode.label} • ${parsed.format.ext.uppercase(Locale.US)})...",
                isError = false,
                isRunning = true,
                isR2Command = currentR2Mode
            )
            onProgress(baseEntry)
            val res = ForgeDumpGenerator.runDump(
                context = context,
                targetFile = activeFile,
                mode = parsed.mode,
                format = parsed.format,
                force = parsed.force,
                isCancelled = { cancelledSessions.contains(sessionId) },
                onProcessStarted = { proc -> activeProcesses[sessionId] = proc },
                onProcessFinished = { activeProcesses.remove(sessionId) }
            ) { statusMsg ->
                if (!cancelledSessions.contains(sessionId)) {
                    onProgress(baseEntry.copy(output = statusMsg, isRunning = true))
                }
            }
            return res.fold(
                onSuccess = { (savedFile, summary) ->
                    InteractiveCommandOutcome(
                        entry = baseEntry.copy(output = summary, isError = false, isRunning = false),
                        nextR2Mode = currentR2Mode,
                        nextSeekHex = currentSeekHex,
                        createdDumpFile = savedFile
                    )
                },
                onFailure = { err ->
                    val stopped = err is CancellationException || cancelledSessions.contains(sessionId)
                    InteractiveCommandOutcome(
                        entry = baseEntry.copy(
                            output = if (stopped) "[dump] Stopped by user." else "[dump error] ${err.message ?: "Failed to dump binary"}",
                            isError = !stopped,
                            isRunning = false
                        ),
                        nextR2Mode = currentR2Mode,
                        nextSeekHex = currentSeekHex
                    )
                }
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
                        appendLine("  dump [mode] [fmt] - Generate Il2Cpp-style dump (txt/md/json) to Vault")
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
                "-N",
                "-e", "scr.color=0",
                "-e", "scr.utf8=0",
                "-e", "scr.interactive=false",
                "-e", "scr.prompt=false",
                "-e", "bin.relocs.apply=true",
                "-q",
                targetFile.absolutePath
            )
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            val libDir = r2Bin.parentFile?.absolutePath.orEmpty()
            val prevLd = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = if (prevLd.isBlank()) libDir else "$libDir:$prevLd"
            env["HOME"] = File(context.filesDir, "r2home").apply { mkdirs() }.absolutePath
            env["TMPDIR"] = File(context.cacheDir, "r2tmp").apply { mkdirs() }.absolutePath
            env["R2_NOPLUGINS"] = "1"

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
            val isPureAaa = r2Command.trim().equals("aaa", ignoreCase = true)
            val targetSha = if (isPureAaa) {
                runCatching { ForgeDumpGenerator.sha256File(targetFile) }.getOrNull()
            } else {
                null
            }
            val useCachedAaa = isPureAaa && targetSha != null && ForgeDumpGenerator.isAaaCached(context, targetSha)
            val effectiveR2Cmd = when {
                useCachedAaa && targetSha != null -> "Po ${ForgeDumpGenerator.r2ProjectKey(targetSha)}"
                isPureAaa && targetSha != null -> "aaa; Ps ${ForgeDumpGenerator.r2ProjectKey(targetSha)}"
                else -> r2Command
            }
            synchronized(sess.writer) {
                sess.writer.write(effectiveR2Cmd)
                sess.writer.newLine()
                sess.writer.write("?e $marker")
                sess.writer.newLine()
                sess.writer.flush()
            }
            if (isPureAaa && targetSha != null && !useCachedAaa) {
                ForgeDumpGenerator.markAaaCached(context, targetSha)
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

                val cleanedLine = cleanTerminalLine(line)
                if (cleanedLine.contains(marker)) {
                    val beforeMarker = cleanedLine.substringBefore(marker).trimEnd()
                    if (beforeMarker.isNotEmpty() && collected.length < 80_000) {
                        if (collected.isNotEmpty()) collected.append('\n')
                        collected.append(beforeMarker)
                    }
                    break
                }

                if (cleanedLine.isNotEmpty() || collected.isNotEmpty()) {
                    if (collected.length < 80_000) {
                        if (collected.isNotEmpty()) collected.append('\n')
                        collected.append(cleanedLine)
                    } else if (!collected.endsWith("...[UI buffer capped at 80 KB — use 'dump' for full export]")) {
                        collected.append("\n...[UI buffer capped at 80 KB — use 'dump' for full export]")
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUiPush >= 400L) {
                    onProgress(baseEntry.copy(output = collected.toString(), isRunning = true))
                    lastUiPush = now
                }
            }

            // Query current seek offset and strictly extract clean 0x... hex token
            if (!aborted && sess.process.isAlive) {
                val seekOut = querySeekOffset(sess)
                val cleanHex = seekOut?.let { Regex("0x[0-9a-fA-F]+").find(it)?.value }
                if (cleanHex != null) {
                    detectedSeek = cleanHex
                } else if (r2Command.startsWith("s ")) {
                    val candidate = r2Command.removePrefix("s ").trim()
                    val mHex = Regex("0x[0-9a-fA-F]+").find(candidate)?.value
                    if (mHex != null) {
                        detectedSeek = mHex
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
            !aborted && r2Command.trim().equals("aaa", ignoreCase = true) -> "[r2 cache] Analysis ready for ${targetFile.name}."
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

    private fun cleanTerminalLine(raw: String): String {
        return raw
            .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)"), "")
            .replace(Regex("\\[\\?[0-9;]+[hl]"), "")
            .replace("\r", "")
            .trimEnd()
    }
}
