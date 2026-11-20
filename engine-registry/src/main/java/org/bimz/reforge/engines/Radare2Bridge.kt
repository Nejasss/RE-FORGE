package org.bimz.reforge.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class Radare2ExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long = 0L
)

class Radare2Bridge(private val context: Context) {

    fun resolveBinary(): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeDir, "libr2.so")
        if (candidate.exists() && candidate.canExecute()) {
            return candidate
        }
        return null
    }

    fun isAvailable(): Boolean = resolveBinary() != null

    suspend fun getVersion(): String = withContext(Dispatchers.IO) {
        val bin = resolveBinary() ?: return@withContext "Not bundled in nativeLibraryDir"
        val res = runCommand(bin, null, listOf("-v"), timeoutSeconds = 15, onProgress = null)
        if (res.success) res.stdout.lines().firstOrNull()?.trim().orEmpty() else res.stderr
    }

    suspend fun executeSafeQuery(targetFile: File?, command: String): Radare2ExecutionResult =
        executeCommand(targetFile, command)

    suspend fun executeCommand(targetFile: File?, command: String): Radare2ExecutionResult =
        executeCommandStreaming(targetFile, command, onProgress = null)

    suspend fun executeCommandStreaming(
        targetFile: File?,
        command: String,
        onProgress: ((String) -> Unit)?
    ): Radare2ExecutionResult = withContext(Dispatchers.IO) {
        val bin = resolveBinary()
            ?: return@withContext Radare2ExecutionResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "Radare2 ARM64 binary (libr2.so) is not bundled in this APK build."
            )

        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return@withContext Radare2ExecutionResult(
                success = false,
                exitCode = -2,
                stdout = "",
                stderr = "Empty Radare2 command."
            )
        }

        if (targetFile != null && (!targetFile.exists() || !targetFile.isFile)) {
            return@withContext Radare2ExecutionResult(
                success = false,
                exitCode = -3,
                stdout = "",
                stderr = "Target file does not exist: ${targetFile.absolutePath}"
            )
        }

        val isAnalysisCmd = trimmed.split(';', ' ').any { token ->
            val t = token.trim()
            t == "aa" || t == "aaa" || t == "aaaa" || t == "AA" || t == "aac" || t == "aar"
        }

        val effectiveCmd = if (isAnalysisCmd && !trimmed.contains(";")) {
            "$trimmed; ?e [r2] Functions discovered: ; afl~?"
        } else {
            trimmed
        }

        val argv = mutableListOf(
            "-N",
            "-q",
            "-e", "scr.color=0",
            "-e", "scr.interactive=false",
            "-e", "scr.prompt=false",
            "-e", "bin.relocs.apply=true"
        )
        if (isAnalysisCmd) {
            argv.addAll(listOf("-e", "anal.verbose=true", "-e", "bin.verbose=true"))
        }
        argv.addAll(listOf("-c", effectiveCmd))
        if (targetFile != null) {
            argv.add(targetFile.absolutePath)
        } else {
            argv.add("--")
        }

        runCommand(bin, context.cacheDir, argv, timeoutSeconds = 180, onProgress = onProgress)
    }

    private suspend fun runCommand(
        binary: File,
        workDir: File?,
        args: List<String>,
        timeoutSeconds: Long,
        onProgress: ((String) -> Unit)?
    ): Radare2ExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val configDir = File(context.filesDir, ".config").apply { mkdirs() }
            val dataDir = File(context.filesDir, ".local/share").apply { mkdirs() }

            val fullCmd = listOf(binary.absolutePath) + args
            val pb = ProcessBuilder(fullCmd)
            if (workDir != null) pb.directory(workDir)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.environment()["XDG_CONFIG_HOME"] = configDir.absolutePath
            pb.environment()["XDG_DATA_HOME"] = dataDir.absolutePath
            pb.environment()["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
            pb.environment()["TERM"] = "dumb"

            val process = pb.start()
            runCatching { process.outputStream.close() }

            val stdoutBuf = StringBuffer()
            val stderrBuf = StringBuffer()

            val outThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stdoutBuf.append(line).append('\n')
                            onProgress?.invoke(line)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val errThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stderrBuf.append(line).append('\n')
                            if (line.isNotBlank()) {
                                onProgress?.invoke("[r2:status] $line")
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val tickerJob = if (onProgress != null) {
                launch {
                    var elapsedSec = 0
                    while (isActive && process.isAlive) {
                        delay(1000L)
                        elapsedSec += 1
                        if (process.isAlive) {
                            onProgress.invoke("[r2:progress] Processing... (${elapsedSec}s elapsed)")
                        }
                    }
                }
            } else {
                null
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            tickerJob?.cancel()

            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                val duration = System.currentTimeMillis() - startMs
                return@withContext Radare2ExecutionResult(
                    success = false,
                    exitCode = -4,
                    stdout = stdoutBuf.toString(),
                    stderr = "Radare2 timed out after ${timeoutSeconds}s.",
                    durationMs = duration
                )
            }

            outThread.join(2000)
            errThread.join(2000)
            val exitCode = process.exitValue()
            val duration = System.currentTimeMillis() - startMs
            Radare2ExecutionResult(
                success = exitCode == 0,
                exitCode = exitCode,
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
                durationMs = duration
            )
        } catch (e: Exception) {
            Radare2ExecutionResult(
                success = false,
                exitCode = -5,
                stdout = "",
                stderr = "Execution failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }
}
