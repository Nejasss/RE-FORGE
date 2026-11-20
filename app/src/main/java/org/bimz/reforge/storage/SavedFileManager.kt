package org.bimz.reforge.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

data class SavedForgeFile(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isTextDump: Boolean
)

class SavedFileManager(private val context: Context) {

    val forgeDirectory: File = File(context.filesDir, "RE FORGE").apply { mkdirs() }

    private val textExtensions = setOf("txt", "log", "json", "md", "asm", "dis", "csv", "xml")

    fun listSavedFiles(): List<SavedForgeFile> {
        forgeDirectory.mkdirs()
        return forgeDirectory.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val ext = file.extension.lowercase()
                SavedForgeFile(
                    name = file.name,
                    file = file,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    isTextDump = ext in textExtensions
                )
            }
            .orEmpty()
    }

    fun saveTextDump(fileName: String, content: String): SavedForgeFile {
        val safeName = sanitizeFileName(fileName)
        val internalFile = File(forgeDirectory, safeName)
        internalFile.writeText(content, Charsets.UTF_8)
        mirrorToPublicDownloads(safeName, "text/plain", content.toByteArray(Charsets.UTF_8))
        return SavedForgeFile(
            name = internalFile.name,
            file = internalFile,
            sizeBytes = internalFile.length(),
            lastModifiedMs = internalFile.lastModified(),
            isTextDump = true
        )
    }

    fun saveBinaryBytes(fileName: String, bytes: ByteArray): SavedForgeFile {
        val safeName = sanitizeFileName(fileName)
        val internalFile = File(forgeDirectory, safeName)
        internalFile.writeBytes(bytes)
        mirrorToPublicDownloads(safeName, "application/octet-stream", bytes)
        val ext = internalFile.extension.lowercase()
        return SavedForgeFile(
            name = internalFile.name,
            file = internalFile,
            sizeBytes = internalFile.length(),
            lastModifiedMs = internalFile.lastModified(),
            isTextDump = ext in textExtensions
        )
    }

    fun importUri(uri: Uri): SavedForgeFile? {
        val displayName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.bin"
        val safeName = sanitizeFileName(displayName)
        val destFile = File(forgeDirectory, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        mirrorToPublicDownloads(safeName, "application/octet-stream", destFile.readBytes())
        val ext = destFile.extension.lowercase()
        return SavedForgeFile(
            name = destFile.name,
            file = destFile,
            sizeBytes = destFile.length(),
            lastModifiedMs = destFile.lastModified(),
            isTextDump = ext in textExtensions
        )
    }

    fun deleteFile(item: SavedForgeFile): Boolean {
        return item.file.exists() && item.file.delete()
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.ifEmpty { "reforge_${System.currentTimeMillis()}.bin" }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun mirrorToPublicDownloads(fileName: String, mimeType: String, data: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RE FORGE")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(data)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val pubDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "RE FORGE"
                )
                pubDir.mkdirs()
                File(pubDir, fileName).writeBytes(data)
            }
        } catch (_: Exception) {
        }
    }
}
