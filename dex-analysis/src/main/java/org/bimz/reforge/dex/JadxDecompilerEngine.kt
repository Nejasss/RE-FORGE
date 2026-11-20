package org.bimz.reforge.dex

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DecompiledJavaResult(
    val className: String,
    val code: String,
    val disclaimer: String = "Decompiled Java source is an approximation reconstructed from Dalvik bytecode by JADX Core."
)

class JadxDecompilerEngine {

    suspend fun decompileClass(
        targetFile: File,
        javaClassName: String,
        tempOutDir: File
    ): Result<DecompiledJavaResult> = withContext(Dispatchers.IO) {
        runCatching {
            tempOutDir.mkdirs()
            val args = JadxArgs().apply {
                inputFiles = listOf(targetFile)
                outDir = tempOutDir
                isSkipResources = true
                threadsCount = 2
                isShowInconsistentCode = true
            }
            JadxDecompiler(args).use { jadx ->
                jadx.load()
                val cls = jadx.classes.firstOrNull { it.fullName == javaClassName }
                    ?: throw NoSuchElementException("Class not found in JADX index: $javaClassName")
                val code = cls.code
                DecompiledJavaResult(
                    className = javaClassName,
                    code = code
                )
            }
        }
    }
}
