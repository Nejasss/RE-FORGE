package org.bimz.reforge

import com.android.apksig.ApkSigner
import kotlinx.coroutines.runBlocking
import org.bimz.reforge.core.model.CheckStatus
import org.bimz.reforge.signing.ApkVerificationEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

class SigningAndVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectsUnsignedApkAndPassesStructuralChecks() = runBlocking {
        val rawApk = tempFolder.newFile("sample.apk")
        ZipOutputStream(FileOutputStream(rawApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest package=\"org.bimz.sample\"/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0a))
            zos.closeEntry()
        }

        val verifier = ApkVerificationEngine()
        val report = verifier.verifyApk(rawApk)
        assertEquals(CheckStatus.FAILED, report.overallStatus)
        val manifestCheck = report.items.first { it.checkName == "AndroidManifest.xml Presence" }
        assertEquals(CheckStatus.PASS, manifestCheck.status)
    }
}
