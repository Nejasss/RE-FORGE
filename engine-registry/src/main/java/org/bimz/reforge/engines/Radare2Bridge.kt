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
        val res = runCommand(bin, null, listOf("-v"))
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
                "-q",
                "-e", "scr.color=0",
                "-e", "scr.interactive=false",
                "-e", "bin.relocs.apply=true",
                "-c", trimmed
            )
            if (targetFile != null) {
                argv.add(targetFile.absolutePath)
            } else {
                argv.add("--")
            }
            runCommand(bin, context.cacheDir, argv)
        }

    private fun runCommand(binary: File, workDir: File?, args: List<String>): Radare2ExecutionResult {
        return try {
            val fullCmd = listOf(binary.absolutePath) + args
            val pb = ProcessBuilder(fullCmd)
            if (workDir != null) pb.directory(workDir)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val process = pb.start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Radare2ExecutionResult(false, -4, "", "Radare2 analysis timed out after 60 seconds")
            }
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            Radare2ExecutionResult(process.exitValue() == 0, process.exitValue(), out, err)
        } catch (e: Exception) {
            Radare2ExecutionResult(false, -5, "", "Execution failed: ${e.message}")
        }
    }
}
