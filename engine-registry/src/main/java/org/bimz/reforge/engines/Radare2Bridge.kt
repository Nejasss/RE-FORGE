package org.bimz.reforge.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class R2CommandResult(
    val success: Boolean,
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val currentSeekHex: String = "0x00000000"
)

class Radare2Bridge(private val context: Context) {

    private class SessionR2Holder(
        val targetPath: String?,
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val stderrQueue: ConcurrentLinkedQueue<String>,
        @Volatile var liveStderrSink: ((String) -> Unit)? = null
    )

    private val sessionProcesses = ConcurrentHashMap<String, SessionR2Holder>()

    fun locateRadare2Binary(): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val packagedSo = File(nativeDir, "libr2.so")
        if (packagedSo.exists() && packagedSo.canExecute()) {
            return packagedSo
        }
        val fallbackBins = listOf(
            File(nativeDir, "libradare2.so"),
            File(context.filesDir, "bin/r2")
        )
        return fallbackBins.firstOrNull { it.exists() && it.canExecute() }
    }

    fun isAvailable(): Boolean = locateRadare2Binary() != null

    fun versionString(): String {
        val bin = locateRadare2Binary() ?: return "Unavailable"
        return try {
            val pb = ProcessBuilder(bin.absolutePath, "-v")
            configureEnv(pb)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            out.lineSequence().firstOrNull()?.take(48) ?: "ARM64 Binary Ready"
        } catch (e: Exception) {
            "Binary Present (${e.javaClass.simpleName})"
        }
    }

    private fun configureEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val existingLd = env["LD_LIBRARY_PATH"].orEmpty()
        env["LD_LIBRARY_PATH"] = if (existingLd.isEmpty()) nativeDir else "$nativeDir:$existingLd"
        val r2Home = File(context.filesDir, "r2home").apply { mkdirs() }
        val r2Tmp = File(context.cacheDir, "r2tmp").apply { mkdirs() }
        env["HOME"] = r2Home.absolutePath
        env["TMPDIR"] = r2Tmp.absolutePath
        env["R2_NOPLUGINS"] = "1"
    }

    private fun getOrStartSessionProcess(
        sessionId: String,
        r2Bin: File,
        targetFile: File?
    ): SessionR2Holder {
        val desiredPath = targetFile?.absolutePath
        val existing = sessionProcesses[sessionId]
        if (existing != null && existing.process.isAlive && existing.targetPath == desiredPath) {
            return existing
        }
        if (existing != null) {
            closeSession(sessionId)
        }

        val args = mutableListOf(
            r2Bin.absolutePath,
            "-N",
            "-q",
            "-e", "scr.color=0",
            "-e", "scr.prompt=false",
            "-e", "bin.relocs.apply=true"
        )
        if (targetFile != null && targetFile.exists()) {
            args.add(targetFile.absolutePath)
        } else {
            args.add("-")
        }

        val pb = ProcessBuilder(args)
        pb.directory(context.filesDir)
        configureEnv(pb)
        val process = pb.start()

        val stderrQueue = ConcurrentLinkedQueue<String>()
        val holder = SessionR2Holder(
            targetPath = desiredPath,
            process = process,
            writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)),
            reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)),
            stderrQueue = stderrQueue
        )

        thread(isDaemon = true, name = "r2-stderr-$sessionId") {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { errReader ->
                    while (true) {
                        val line = errReader.readLine() ?: break
                        if (line.isNotBlank()) {
                            stderrQueue.add(line)
                            holder.liveStderrSink?.invoke(line)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        sessionProcesses[sessionId] = holder
        return holder
    }

    fun stopSession(sessionId: String) {
        val holder = sessionProcesses.remove(sessionId) ?: return
        try {
            holder.process.destroyForcibly()
        } catch (_: Exception) {
        }
    }

    fun closeSession(sessionId: String) {
        val holder = sessionProcesses.remove(sessionId) ?: return
        try {
            holder.writer.write("q!\n")
            holder.writer.flush()
        } catch (_: Exception) {
        }
        try {
            holder.process.destroyForcibly()
        } catch (_: Exception) {
        }
    }

    fun closeAllSessions() {
        val keys = sessionProcesses.keys.toList()
        keys.forEach { closeSession(it) }
    }

    suspend fun executeCommand(
        targetFile: File?,
        command: String,
        timeoutSeconds: Long = 0L
    ): R2CommandResult = executeCommandStreaming(targetFile, command, "oneshot") {}

    suspend fun executeCommandStreaming(
        targetFile: File?,
        command: String,
        sessionId: String = "default",
        onProgress: (String) -> Unit
    ): R2CommandResult = withContext(Dispatchers.IO) {
        val r2Bin = locateRadare2Binary()
            ?: return@withContext R2CommandResult(
                success = false,
                command = command,
                stdout = "",
                stderr = "Radare2 ARM64 engine binary (libr2.so) not packaged in nativeLibraryDir.",
                exitCode = -1,
                durationMs = 0L
            )

        val sanitizedCommand = command.trim().ifEmpty { "i" }
        val start = System.currentTimeMillis()
        val marker = "__REFORGE_EOC_${UUID.randomUUID()}__"
        val seekMarker = "${marker}_SEEK_"
        val endMarker = "${marker}_END_"

        val tickerJob = launch {
            while (isActive) {
                delay(1000L)
                val sec = (System.currentTimeMillis() - start) / 1000L
                val min = sec / 60L
                val remSec = sec % 60L
                val elapsedFmt = if (min > 0L) "${min}m ${remSec}s" else "${remSec}s"
                onProgress("[r2:progress] Running '$sanitizedCommand' ($elapsedFmt elapsed — unlimited time)...")
            }
        }

        try {
            val holder = getOrStartSessionProcess(sessionId, r2Bin, targetFile)
            holder.stderrQueue.clear()
            holder.liveStderrSink = { errLine -> onProgress(errLine) }

            synchronized(holder.writer) {
                holder.writer.write(sanitizedCommand)
                holder.writer.newLine()
                holder.writer.write("?e $seekMarker")
                holder.writer.newLine()
                holder.writer.write("?v $$")
                holder.writer.newLine()
                holder.writer.write("?e $endMarker")
                holder.writer.newLine()
                holder.writer.flush()
            }

            val outBuilder = StringBuilder()
            var readingSeek = false
            var latestSeek = "0x00000000"
            var completedNormally = false

            while (true) {
                val line = holder.reader.readLine() ?: break
                when {
                    line == seekMarker -> {
                        readingSeek = true
                    }
                    line == endMarker -> {
                        completedNormally = true
                        break
                    }
                    readingSeek -> {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("0x")) {
                            latestSeek = trimmed
                        }
                        readingSeek = false
                    }
                    else -> {
                        outBuilder.appendLine(line)
                        if (line.isNotBlank()) {
                            onProgress(line)
                        }
                    }
                }
            }

            holder.liveStderrSink = null
            tickerJob.cancel()

            val errLines = mutableListOf<String>()
            while (true) {
                val e = holder.stderrQueue.poll() ?: break
                errLines.add(e)
            }
            val stderrText = errLines.joinToString("\n").trim()
            val elapsed = System.currentTimeMillis() - start

            if (!completedNormally) {
                sessionProcesses.remove(sessionId)
                val exitVal = runCatching { holder.process.exitValue() }.getOrDefault(-2)
                return@withContext R2CommandResult(
                    success = false,
                    command = sanitizedCommand,
                    stdout = outBuilder.toString().trim(),
                    stderr = if (stderrText.isNotEmpty()) stderrText else "[r2] Session process stopped or terminated.",
                    exitCode = exitVal,
                    durationMs = elapsed,
                    currentSeekHex = latestSeek
                )
            }

            R2CommandResult(
                success = true,
                command = sanitizedCommand,
                stdout = outBuilder.toString().trim(),
                stderr = stderrText,
                exitCode = 0,
                durationMs = elapsed,
                currentSeekHex = latestSeek
            )
        } catch (e: Exception) {
            tickerJob.cancel()
            sessionProcesses.remove(sessionId)
            R2CommandResult(
                success = false,
                command = sanitizedCommand,
                stdout = "",
                stderr = "Radare2 session stopped/error: ${e.message ?: e.javaClass.simpleName}",
                exitCode = -3,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }
}
