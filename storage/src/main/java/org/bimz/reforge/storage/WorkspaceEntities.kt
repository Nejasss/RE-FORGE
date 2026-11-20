package org.bimz.reforge.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
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
    val detectedFormatName: String,
    val magicHex: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val notes: String
)

@Entity(tableName = "analysis_history")
data class AnalysisHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val projectName: String,
    val engineName: String,
    val summary: String,
    val timestampEpochMs: Long
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val targetLabel: String,
    val locationRef: String,
    val note: String,
    val createdAtEpochMs: Long
)
