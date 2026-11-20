package org.bimz.reforge.core.security

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

object SafeArchiveExtractor {

    private const val MAX_ENTRY_COUNT = 20_000
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1_500_000_000L

    fun resolveSafeTarget(destDir: File, entryName: String): File {
        if (entryName.isBlank()) {
            throw SecurityException("Empty ZIP entry path rejected")
        }
        val canonicalRoot = destDir.canonicalFile
        val targetFile = File(canonicalRoot, entryName).canonicalFile
        val rootPathWithSep = canonicalRoot.path + File.separator
        if (targetFile != canonicalRoot && !targetFile.path.startsWith(rootPathWithSep)) {
            throw SecurityException("ZIP path traversal blocked for entry: $entryName")
        }
        return targetFile
    }

    fun extractSafely(archiveFile: File, destDir: File): Int {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Cannot create extraction directory: ${destDir.absolutePath}")
        }
        var entriesExtracted = 0
        var totalBytesWritten = 0L
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(FileInputStream(archiveFile).buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entriesExtracted++
                if (entriesExtracted > MAX_ENTRY_COUNT) {
                    throw SecurityException("Archive exceeds maximum safe entry count ($MAX_ENTRY_COUNT)")
                }
                val outFile = resolveSafeTarget(destDir, entry.name)
                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw IOException("Failed creating directory: ${outFile.absolutePath}")
                    }
                } else {
                    val parent = outFile.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Failed creating parent directory: ${parent.absolutePath}")
                    }
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            totalBytesWritten += read
                            if (totalBytesWritten > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw SecurityException("Archive exceeds uncompressed size safety limit")
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        return entriesExtracted
    }
}
