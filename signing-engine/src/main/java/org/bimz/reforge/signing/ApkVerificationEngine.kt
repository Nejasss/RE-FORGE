package org.bimz.reforge.signing

import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.ApkVerificationReport
import org.bimz.reforge.core.model.CheckStatus
import org.bimz.reforge.core.model.VerificationItem
import org.bimz.reforge.core.security.HashCalculator
import java.io.File
import java.util.zip.ZipFile

class ApkVerificationEngine {

    suspend fun verifyApk(apkFile: File): ApkVerificationReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<VerificationItem>()

        if (!apkFile.exists() || apkFile.length() == 0L) {
            checks.add(VerificationItem("File Existence", CheckStatus.FAILED, "APK file missing or empty"))
            return@withContext ApkVerificationReport(apkFile.absolutePath, "", CheckStatus.FAILED, checks)
        }

        val hash = HashCalculator.computeReport(apkFile)
        checks.add(VerificationItem("Output SHA-256", CheckStatus.PASS, hash.sha256))

        var hasManifest = false
        var hasDex = false
        var hasArm64Native = false
        var hasOtherNative = false
        var traversalViolation = false

        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    count++
                    if (e.name.contains("..")) traversalViolation = true
                    if (e.name == "AndroidManifest.xml") hasManifest = true
                    if (e.name.endsWith(".dex") && !e.name.contains("/")) {
                        val magic = ByteArray(4)
                        zip.getInputStream(e).use { it.read(magic) }
                        if (magic[0] == 'd'.code.toByte() && magic[1] == 'e'.code.toByte() && magic[2] == 'x'.code.toByte()) {
                            hasDex = true
                        }
                    }
                    if (e.name.startsWith("lib/arm64-v8a/") && e.name.endsWith(".so")) {
                        hasArm64Native = true
                    } else if (e.name.startsWith("lib/") && e.name.endsWith(".so")) {
                        hasOtherNative = true
                    }
                }
                checks.add(
                    VerificationItem(
                        "ZIP Structural Integrity",
                        if (!traversalViolation && count > 0) CheckStatus.PASS else CheckStatus.FAILED,
                        "Verified $count ZIP entries; traversalCheck=${!traversalViolation}"
                    )
                )
            }
        } catch (e: Exception) {
            checks.add(VerificationItem("ZIP Structural Integrity", CheckStatus.FAILED, "Corrupt ZIP: ${e.message}"))
            return@withContext ApkVerificationReport(apkFile.absolutePath, hash.sha256, CheckStatus.FAILED, checks)
        }

        checks.add(
            VerificationItem(
                "AndroidManifest.xml Presence",
                if (hasManifest) CheckStatus.PASS else CheckStatus.FAILED,
                if (hasManifest) "AndroidManifest.xml present at archive root" else "Missing AndroidManifest.xml"
            )
        )

        checks.add(
            VerificationItem(
                "DEX Bytecode Header",
                if (hasDex) CheckStatus.PASS else CheckStatus.WARNING,
                if (hasDex) "Valid Dalvik DEX magic verified" else "No root classes*.dex detected (resource-only APK)"
            )
        )

        val abiStatus = when {
            hasArm64Native -> CheckStatus.PASS
            hasOtherNative -> CheckStatus.WARNING
            else -> CheckStatus.PASS
        }
        val abiDetail = when {
            hasArm64Native -> "Contains native ARM64 libraries (lib/arm64-v8a/*.so)"
            hasOtherNative -> "Contains non-ARM64 native libraries without arm64-v8a slice"
            else -> "Architecture-independent bytecode/resource APK (no native .so)"
        }
        checks.add(VerificationItem("Native Library ABI", abiStatus, abiDetail))

        try {
            val result = ApkVerifier.Builder(apkFile).build().verify()
            val sigStatus = when {
                result.isVerified && result.warnings.isEmpty() -> CheckStatus.PASS
                result.isVerified -> CheckStatus.WARNING
                else -> CheckStatus.FAILED
            }
            val schemes = buildList {
                if (result.isVerifiedUsingV1Scheme) add("v1")
                if (result.isVerifiedUsingV2Scheme) add("v2")
                if (result.isVerifiedUsingV3Scheme) add("v3")
            }.joinToString(", ").ifEmpty { "none" }
            val detail = if (result.isVerified) {
                "Verified signature schemes: $schemes"
            } else {
                "Signature verification failed: ${result.errors.joinToString("; ")}"
            }
            checks.add(VerificationItem("APK Signature Validity", sigStatus, detail))
        } catch (e: Exception) {
            checks.add(VerificationItem("APK Signature Validity", CheckStatus.FAILED, "Verifier error: ${e.message}"))
        }

        val overall = when {
            checks.any { it.status == CheckStatus.FAILED } -> CheckStatus.FAILED
            checks.any { it.status == CheckStatus.WARNING } -> CheckStatus.WARNING
            else -> CheckStatus.PASS
        }
        ApkVerificationReport(
            targetApkPath = apkFile.absolutePath,
            outputSha256 = hash.sha256,
            overallStatus = overall,
            items = checks
        )
    }
}
