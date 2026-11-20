package org.bimz.reforge.rebuild

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.FileFormat
import org.bimz.reforge.core.model.RebuildResult
import org.bimz.reforge.core.model.RebuildStatus
import org.bimz.reforge.core.model.WorkspaceProject
import org.bimz.reforge.dex.DexInspectorEngine
import org.bimz.reforge.patch.PatchWorkspaceManager
import org.bimz.reforge.resources.ResourceInspectorEngine
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rebuilds authorized APK workspaces with 4-byte general alignment and
 * 16384-byte page alignment for uncompressed native shared libraries (.so).
 */
class ApkRebuildEngine(
    private val dexEngine: DexInspectorEngine,
    private val patchManager: PatchWorkspaceManager,
    private val resourceInspector: ResourceInspectorEngine
) {
    suspend fun rebuildWorkspaceApk(project: WorkspaceProject): RebuildResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        log.appendLine("[REBUILD] Starting workspace validation for ${project.originalFileName}")

        if (project.detectedFormat != FileFormat.APK && project.detectedFormat != FileFormat.ZIP) {
            log.appendLine("[REBUILD] Unsupported format for APK packaging: ${project.detectedFormat.label}")
            return@withContext RebuildResult(
                status = RebuildStatus.UNSUPPORTED,
                outputApkPath = null,
                changedFileCount = 0,
                buildLog = log.toString()
            )
        }

        val workingSource = File(project.workingPath)
        if (!workingSource.exists()) {
            log.appendLine("[REBUILD] Working source file missing: ${workingSource.absolutePath}")
            return@withContext RebuildResult(RebuildStatus.FAILED, null, 0, log.toString())
        }

        val buildDir = File(project.workspaceDir, "build").apply { mkdirs() }
        val smaliDir = patchManager.getSmaliStagingDir(project)
        val overlayDir = patchManager.getOverlayFilesDir(project)

        val smaliFiles = smaliDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()
        val overlayFiles = overlayDir.walkTopDown().filter { it.isFile }.toList()
        val totalChanges = smaliFiles.size + overlayFiles.size

        for (ov in overlayFiles) {
            if (ov.extension.equals("xml", ignoreCase = true)) {
                val xmlRes = resourceInspector.validateXmlSyntax(ov.readText(Charsets.UTF_8))
                if (xmlRes.isFailure) {
                    log.appendLine("[REBUILD] XML validation failed in ${ov.name}: ${xmlRes.exceptionOrNull()?.message}")
                    return@withContext RebuildResult(RebuildStatus.FAILED, null, totalChanges, log.toString())
                }
            }
        }

        val overlayMap = mutableMapOf<String, ByteArray>()
        for (ov in overlayFiles) {
            val relPath = ov.relativeTo(overlayDir).path.replace('\\', '/')
            overlayMap[relPath] = ov.readBytes()
            log.appendLine("[REBUILD] Staged overlay entry: $relPath (${ov.length()} bytes)")
        }

        if (smaliFiles.isNotEmpty()) {
            log.appendLine("[REBUILD] Assembling ${smaliFiles.size} staged Smali file(s) with Google Smali assembler...")
            val assembledDex = File(buildDir, "patched_classes.dex")
            val asmResult = dexEngine.assembleSmaliDirectoryToDex(smaliDir, assembledDex)
            if (asmResult.isFailure) {
                log.appendLine("[REBUILD] Smali assembly failed: ${asmResult.exceptionOrNull()?.message}")
                return@withContext RebuildResult(RebuildStatus.FAILED, null, totalChanges, log.toString())
            }
            overlayMap["classes.dex"] = assembledDex.readBytes()
            log.appendLine("[REBUILD] Smali assembled into classes.dex (${assembledDex.length()} bytes)")
        }

        val unsignedAlignedApk = File(buildDir, "unsigned-aligned.apk")
        try {
            writePageAlignedApk(workingSource, unsignedAlignedApk, overlayMap, log)
            log.appendLine("[REBUILD] Output generated: ${unsignedAlignedApk.absolutePath} (${unsignedAlignedApk.length()} bytes)")
            RebuildResult(
                status = RebuildStatus.SUPPORTED,
                outputApkPath = unsignedAlignedApk.absolutePath,
                changedFileCount = totalChanges,
                buildLog = log.toString()
            )
        } catch (e: Exception) {
            log.appendLine("[REBUILD] Packaging error: ${e.message}")
            RebuildResult(RebuildStatus.FAILED, null, totalChanges, log.toString())
        }
    }

    private fun writePageAlignedApk(
        baseApk: File,
        outApk: File,
        replacements: Map<String, ByteArray>,
        log: StringBuilder
    ) {
        val writtenNames = mutableSetOf<String>()
        var currentOffset = 0L

        ZipFile(baseApk).use { inZip ->
            ZipOutputStream(FileOutputStream(outApk).buffered()).use { zos ->
                val entries = inZip.entries()
                while (entries.hasMoreElements()) {
                    val srcEntry = entries.nextElement()
                    val name = srcEntry.name
                    if (srcEntry.isDirectory) continue
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") || name == "META-INF/MANIFEST.MF")
                    ) {
                        continue
                    }

                    val data = replacements[name] ?: inZip.getInputStream(srcEntry).use { it.readBytes() }
                    writtenNames.add(name)
                    currentOffset = writeAlignedEntry(zos, name, data, currentOffset)
                }

                for ((name, data) in replacements) {
                    if (!writtenNames.contains(name)) {
                        currentOffset = writeAlignedEntry(zos, name, data, currentOffset)
                    }
                }
            }
        }
        log.appendLine("[REBUILD] Wrote ${writtenNames.size} APK entries with 16KB .so alignment.")
    }

    private fun writeAlignedEntry(
        zos: ZipOutputStream,
        name: String,
        data: ByteArray,
        currentOffset: Long
    ): Long {
        val storeUncompressed = name.endsWith(".so") || name == "resources.arsc"
        val alignment = if (name.endsWith(".so")) 16384L else 4L
        val localHeaderLen = 30L + name.toByteArray(Charsets.UTF_8).size
        val paddingNeeded = if (storeUncompressed) {
            val rem = (currentOffset + localHeaderLen) % alignment
            if (rem == 0L) 0 else (alignment - rem).toInt()
        } else {
            0
        }

        val entry = ZipEntry(name).apply {
            if (paddingNeeded > 0) {
                extra = ByteArray(paddingNeeded)
            }
            if (storeUncompressed) {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                val crc32Calc = CRC32().apply { update(data) }
                crc = crc32Calc.value
            } else {
                method = ZipEntry.DEFLATED
            }
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
        return currentOffset + localHeaderLen + paddingNeeded + data.size
    }
}
