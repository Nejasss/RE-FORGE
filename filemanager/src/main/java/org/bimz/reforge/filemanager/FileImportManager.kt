package org.bimz.reforge.filemanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.FileFormat
import org.bimz.reforge.core.model.WorkspaceProject
import org.bimz.reforge.core.security.HashCalculator
import org.bimz.reforge.storage.WorkspaceRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class FileImportManager(
    private val context: Context,
    private val repository: WorkspaceRepository
) {
    suspend fun importFromUri(uri: Uri): Result<WorkspaceProject> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolveDisplayName(uri)
            val safeFileName = sanitizeFileName(displayName)
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"

            val projectId = UUID.randomUUID().toString().take(12)
            val projectDir = File(repository.workspacesRoot, projectId).apply { mkdirs() }
            val originalDir = File(projectDir, "original").apply { mkdirs() }
            val workingDir = File(projectDir, "working").apply { mkdirs() }
            File(projectDir, "build").mkdirs()

            val originalFile = File(originalDir, safeFileName)
            val workingFile = File(workingDir, safeFileName)

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(originalFile).use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            } ?: throw IOException("Unable to open input stream from Storage Access Framework URI")

            if (!originalFile.exists() || originalFile.length() == 0L) {
                throw IOException("Imported file is empty (0 bytes)")
            }

            originalFile.copyTo(workingFile, overwrite = true)
            originalFile.setReadOnly()

            val hashReport = HashCalculator.computeReport(originalFile)
            val headerBytes = originalFile.inputStream().use { ins ->
                val buf = ByteArray(32)
                val n = ins.read(buf)
                if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
            val format = FileFormat.detect(safeFileName, headerBytes)
            val now = System.currentTimeMillis()

            val project = WorkspaceProject(
                id = projectId,
                name = safeFileName.substringBeforeLast('.').ifBlank { safeFileName },
                originalFileName = safeFileName,
                originalPath = originalFile.absolutePath,
                workingPath = workingFile.absolutePath,
                workspaceDir = projectDir.absolutePath,
                fileSize = hashReport.fileSize,
                sha256 = hashReport.sha256,
                sha1 = hashReport.sha1,
                md5 = hashReport.md5,
                crc32 = hashReport.crc32,
                mimeType = mimeType,
                detectedFormat = format,
                magicHex = hashReport.magicHex,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )

            repository.saveProject(project)
            repository.recordAnalysis(
                projectId = project.id,
                projectName = project.name,
                engineName = "FileImportManager",
                summary = "Imported ${project.originalFileName} (${project.detectedFormat.label}, ${project.fileSize} bytes)"
            )
            project
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "imported_file.bin" } ?: "imported_file.bin"
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "imported_file.bin" }
    }
}
