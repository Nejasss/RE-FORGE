package org.bimz.reforge.core.model

data class WorkspaceProject(
    val id: String,
    val name: String,
    val originalFileName: String,
    val originalPath: String,
    val workingPath: String,
    val workspaceDir: String,
    val fileSize: Long,
    val sha256: String,
    val sha1: String,
    val md5: String,
    val crc32: String,
    val mimeType: String,
    val detectedFormat: FileFormat,
    val magicHex: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val notes: String = ""
)

data class FileHashReport(
    val fileName: String,
    val fileSize: Long,
    val sha256: String,
    val sha1: String,
    val md5: String,
    val crc32: String,
    val magicHex: String,
    val signatureLabel: String
)

data class ModifiedEntryRecord(
    val relativePath: String,
    val changeType: ChangeType,
    val originalSize: Long,
    val modifiedSize: Long,
    val timestampEpochMs: Long,
    val validationState: String
)

enum class ChangeType {
    MODIFIED,
    ADDED,
    DELETED
}
