package org.bimz.reforge.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.FileFormat
import org.bimz.reforge.core.model.WorkspaceProject
import java.io.File

class WorkspaceRepository(
    private val context: Context,
    private val dao: WorkspaceDao
) {
    val workspacesRoot: File
        get() = File(context.filesDir, "workspaces").apply { mkdirs() }

    fun observeProjects(): Flow<List<WorkspaceProject>> =
        dao.observeProjects().map { list -> list.map { it.toDomain() } }

    fun observeRecentAnalysis(): Flow<List<AnalysisHistoryEntity>> =
        dao.observeRecentAnalysis()

    fun observeBookmarks(projectId: String): Flow<List<BookmarkEntity>> =
        dao.observeBookmarks(projectId)

    suspend fun saveProject(project: WorkspaceProject) {
        dao.upsertProject(project.toEntity())
    }

    suspend fun updateProjectNotes(projectId: String, notes: String) {
        dao.updateNotes(projectId, notes, System.currentTimeMillis())
    }

    suspend fun recordAnalysis(projectId: String, projectName: String, engineName: String, summary: String) {
        dao.insertAnalysisHistory(
            AnalysisHistoryEntity(
                projectId = projectId,
                projectName = projectName,
                engineName = engineName,
                summary = summary,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun addBookmark(projectId: String, label: String, locationRef: String, note: String) {
        dao.insertBookmark(
            BookmarkEntity(
                projectId = projectId,
                targetLabel = label,
                locationRef = locationRef,
                note = note,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val dir = File(workspacesRoot, projectId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        dao.deleteProject(projectId)
    }

    suspend fun calculateTotalStorageBytes(): Long = withContext(Dispatchers.IO) {
        workspacesRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun ProjectEntity.toDomain(): WorkspaceProject {
        val format = runCatching { FileFormat.valueOf(detectedFormatName) }.getOrDefault(FileFormat.RAW_BINARY)
        return WorkspaceProject(
            id = id,
            name = name,
            originalFileName = originalFileName,
            originalPath = originalPath,
            workingPath = workingPath,
            workspaceDir = workspaceDir,
            fileSize = fileSize,
            sha256 = sha256,
            sha1 = sha1,
            md5 = md5,
            crc32 = crc32,
            mimeType = mimeType,
            detectedFormat = format,
            magicHex = magicHex,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            notes = notes
        )
    }

    private fun WorkspaceProject.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        originalFileName = originalFileName,
        originalPath = originalPath,
        workingPath = workingPath,
        workspaceDir = workspaceDir,
        fileSize = fileSize,
        sha256 = sha256,
        sha1 = sha1,
        md5 = md5,
        crc32 = crc32,
        mimeType = mimeType,
        detectedFormatName = detectedFormat.name,
        magicHex = magicHex,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        notes = notes
    )
}
