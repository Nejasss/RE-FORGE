package org.bimz.reforge

import org.bimz.reforge.core.security.SafeArchiveExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeArchiveExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun blocksZipSlipTraversalPath() {
        val dest = tempFolder.newFolder("extract")
        assertThrows(SecurityException::class.java) {
            SafeArchiveExtractor.resolveSafeTarget(dest, "../evil.sh")
        }
    }

    @Test
    fun extractsValidEntriesSafely() {
        val zipFile = tempFolder.newFile("valid.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf(0x64, 0x65, 0x78, 0x0a))
            zos.closeEntry()
        }
        val dest = tempFolder.newFolder("out")
        val count = SafeArchiveExtractor.extractSafely(zipFile, dest)
        assertEquals(1, count)
        assertEquals(4L, File(dest, "classes.dex").length())
    }
}
