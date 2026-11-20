package org.bimz.reforge.core.security

import org.bimz.reforge.core.model.FileFormat
import org.bimz.reforge.core.model.FileHashReport
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.CRC32

object HashCalculator {

    fun computeReport(file: File): FileHashReport {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = MessageDigest.getInstance("MD5")
        val crc32 = CRC32()
        val header = ByteArray(32)
        var headerCount = 0
        val buffer = ByteArray(128 * 1024)

        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                if (headerCount < header.size) {
                    val copyLen = minOf(header.size - headerCount, read)
                    System.arraycopy(buffer, 0, header, headerCount, copyLen)
                    headerCount += copyLen
                }
                sha256.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
                md5.update(buffer, 0, read)
                crc32.update(buffer, 0, read)
            }
        }

        val actualHeader = header.copyOf(headerCount)
        val format = FileFormat.detect(file.name, actualHeader)
        val magicHex = actualHeader.take(16).joinToString(" ") { "%02X".format(it) }

        return FileHashReport(
            fileName = file.name,
            fileSize = file.length(),
            sha256 = sha256.digest().toHex(),
            sha1 = sha1.digest().toHex(),
            md5 = md5.digest().toHex(),
            crc32 = "%08x".format(crc32.value),
            magicHex = magicHex,
            signatureLabel = format.label
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
