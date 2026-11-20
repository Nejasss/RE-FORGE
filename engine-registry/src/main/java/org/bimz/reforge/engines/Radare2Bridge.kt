package org.bimz.reforge.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class Radare2ExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
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
        val res = runCommand(bin, null, listOf("-v"), timeoutSeconds = 15)
        if (res.success) res.stdout.lines().firstOrNull()?.trim().orEmpty() else res.stderr
    }

    suspend fun executeSafeQuery(targetFile: File?, command: String): Radare2ExecutionResult =
        executeCommand(targetFile, command)

    suspend fun executeCommand(targetFile: File?, command: String): Radare2ExecutionResult =
        withContext(Dispatchers.IO) {
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

            val argv = mutableListOf(
                "-N",
                "-q",
                "-e", "scr.color=0",
                "-e", "scr.interactive=false",
                "-e", "scr.prompt=false",
                "-e", "bin.relocs.apply=true",
                "-c", trimmed
            )
            if (targetFile != null) {
                argv.add(targetFile.absolutePath)
            } else {
                argv.add("--")
            }
            runCommand(bin, context.cacheDir, argv, timeoutSeconds = 120)
        }

    private fun runCommand(
        binary: File,
        workDir: File?,
        args: List<String>,
        timeoutSeconds: Long
    ): Radare2ExecutionResult {
        return try {
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
            // Close stdin immediately so r2 never blocks waiting for interactive input
            runCatching { process.outputStream.close() }

            // Drain stdout & stderr concurrently to prevent 64KB Android pipe buffer deadlock during `aaa`
            var stdoutText = ""
            var stderrText = ""
            val outThread = Thread {
                stdoutText = runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }.apply { isDaemon = true; start() }

            val errThread = Thread {
                stderrText = runCatching {
                    process.errorStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                val partial = buildString {
                    if (stdoutText.isNotBlank()) appendLine(stdoutText.trim())
                    if (stderrText.isNotBlank()) appendLine(stderrText.trim())
                }.trim()
                val msg = if (partial.isNotEmpty()) {
                    "Radare2 timed out after ${timeoutSeconds}s.\nPartial output:\n$partial"
                } else {
                    "Radare2 timed out after ${timeoutSeconds}s."
                }
                return Radare2ExecutionResult(false, -4, stdoutText, msg)
            }

            outThread.join(2000)
            errThread.join(2000)
            val exitCode = process.exitValue()
            Radare2ExecutionResult(exitCode == 0, exitCode, stdoutText, stderrText)
        } catch (e: Exception) {
            Radare2ExecutionResult(false, -5, "", "Execution failed: ${e.message}")
        }
    }
}
