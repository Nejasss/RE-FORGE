package org.bimz.reforge.apk

import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.ApkInspectionReport
import org.bimz.reforge.core.model.ApkSignatureSummary
import org.bimz.reforge.core.model.ApkZipEntryInfo
import org.bimz.reforge.core.model.ManifestComponent
import org.bimz.reforge.core.security.HashCalculator
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class ApkInspectorEngine {

    suspend fun inspectApk(apkFile: File): Result<ApkInspectionReport> = withContext(Dispatchers.IO) {
        runCatching {
            val hash = HashCalculator.computeReport(apkFile)
            val entries = mutableListOf<ApkZipEntryInfo>()
            val nativeAbis = linkedSetOf<String>()
            val dexFiles = mutableListOf<String>()
            var manifestBytes: ByteArray? = null

            ZipFile(apkFile).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val category = when {
                        name == "AndroidManifest.xml" -> "Manifest"
                        name.endsWith(".dex") && !name.contains("/") -> {
                            dexFiles.add(name)
                            "DEX Bytecode"
                        }
                        name.startsWith("lib/") && name.endsWith(".so") -> {
                            val parts = name.split("/")
                            if (parts.size >= 3) nativeAbis.add(parts[1])
                            "Native Library"
                        }
                        name == "resources.arsc" || name.startsWith("res/") -> "Resources"
                        name.startsWith("assets/") -> "Assets"
                        name.startsWith("META-INF/") -> "Signature / Metadata"
                        else -> "Other"
                    }
                    if (name == "AndroidManifest.xml") {
                        manifestBytes = zip.getInputStream(entry).use { it.readBytes() }
                    }
                    entries.add(
                        ApkZipEntryInfo(
                            path = name,
                            category = category,
                            compressedSize = entry.compressedSize,
                            uncompressedSize = entry.size,
                            crc32 = entry.crc,
                            method = entry.method
                        )
                    )
                }
            }

            val parsedAxml = manifestBytes?.let { AxmlBinaryParser.parse(it) }
                ?: throw IllegalStateException("AndroidManifest.xml missing from APK archive")

            val components = extractComponents(parsedAxml.rawElements)
            val sigSummary = verifySignatures(apkFile)

            ApkInspectionReport(
                fileName = apkFile.name,
                fileSize = apkFile.length(),
                sha256 = hash.sha256,
                packageName = parsedAxml.packageName,
                applicationLabel = parsedAxml.applicationLabel,
                versionName = parsedAxml.versionName,
                versionCode = parsedAxml.versionCode,
                minSdkVersion = parsedAxml.minSdkVersion,
                targetSdkVersion = parsedAxml.targetSdkVersion,
                compileSdkVersion = parsedAxml.compileSdkVersion,
                debuggable = parsedAxml.debuggable,
                allowBackup = parsedAxml.allowBackup,
                permissions = parsedAxml.permissions,
                components = components,
                decodedManifestXml = parsedAxml.xmlText,
                entries = entries.sortedBy { it.path },
                nativeAbis = nativeAbis.toList().sorted(),
                dexFiles = dexFiles.sorted(),
                signature = sigSummary
            )
        }
    }

    private fun extractComponents(nodes: List<AxmlElementNode>): List<ManifestComponent> {
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
        val result = mutableListOf<ManifestComponent>()
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            if (node.tagName in componentTags) {
                val compName = node.attributes["android:name"] ?: node.attributes["name"] ?: "unnamed"
                val exportedStr = node.attributes["android:exported"] ?: node.attributes["exported"]
                val exported = when (exportedStr) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                val perm = node.attributes["android:permission"] ?: node.attributes["permission"]
                val actions = mutableListOf<String>()
                val categories = mutableListOf<String>()

                var j = i + 1
                while (j < nodes.size && nodes[j].depth > node.depth) {
                    val child = nodes[j]
                    if (child.tagName == "action") {
                        (child.attributes["android:name"] ?: child.attributes["name"])?.let { actions.add(it) }
                    } else if (child.tagName == "category") {
                        (child.attributes["android:name"] ?: child.attributes["name"])?.let { categories.add(it) }
                    }
                    j++
                }
                result.add(
                    ManifestComponent(
                        type = node.tagName,
                        name = compName,
                        exported = exported,
                        permission = perm,
                        intentActions = actions,
                        intentCategories = categories
                    )
                )
            }
            i++
        }
        return result
    }

    private fun verifySignatures(apkFile: File): ApkSignatureSummary {
        return try {
            val verifier = ApkVerifier.Builder(apkFile).build()
            val res = verifier.verify()
            val subjects = mutableListOf<String>()
            val certHashes = mutableListOf<String>()
            val sha256 = MessageDigest.getInstance("SHA-256")
            for (cert in res.signerCertificates) {
                subjects.add(cert.subjectX500Principal.name)
                val digest = sha256.digest(cert.encoded).joinToString("") { "%02x".format(it) }
                certHashes.add(digest)
            }
            ApkSignatureSummary(
                isSigned = res.isVerified,
                v1Scheme = res.isVerifiedUsingV1Scheme,
                v2Scheme = res.isVerifiedUsingV2Scheme,
                v3Scheme = res.isVerifiedUsingV3Scheme,
                signerSubjects = subjects,
                certSha256List = certHashes,
                warnings = res.warnings.map { it.toString() },
                errors = res.errors.map { it.toString() }
            )
        } catch (e: Exception) {
            ApkSignatureSummary(
                isSigned = false,
                v1Scheme = false,
                v2Scheme = false,
                v3Scheme = false,
                signerSubjects = emptyList(),
                certSha256List = emptyList(),
                warnings = emptyList(),
                errors = listOf("ApkVerifier failed: ${e.message}")
            )
        }
    }
}
