package org.bimz.reforge.core.model

data class ManifestComponent(
    val type: String,
    val name: String,
    val exported: Boolean?,
    val permission: String?,
    val intentActions: List<String>,
    val intentCategories: List<String>
)

data class ApkZipEntryInfo(
    val path: String,
    val category: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val method: Int
)

data class ApkSignatureSummary(
    val isSigned: Boolean,
    val v1Scheme: Boolean,
    val v2Scheme: Boolean,
    val v3Scheme: Boolean,
    val signerSubjects: List<String>,
    val certSha256List: List<String>,
    val warnings: List<String>,
    val errors: List<String>
)

data class ApkInspectionReport(
    val fileName: String,
    val fileSize: Long,
    val sha256: String,
    val packageName: String,
    val applicationLabel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val compileSdkVersion: Int?,
    val debuggable: Boolean,
    val allowBackup: Boolean,
    val permissions: List<String>,
    val components: List<ManifestComponent>,
    val decodedManifestXml: String,
    val entries: List<ApkZipEntryInfo>,
    val nativeAbis: List<String>,
    val dexFiles: List<String>,
    val signature: ApkSignatureSummary
)
