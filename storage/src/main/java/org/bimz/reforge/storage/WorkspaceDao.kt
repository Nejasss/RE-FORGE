package org.bimz.reforge.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    @Query("UPDATE projects SET notes = :notes, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun updateNotes(id: String, notes: String, updatedAt: Long)

    @Query("SELECT * FROM analysis_history ORDER BY timestampEpochMs DESC LIMIT 50")
    fun observeRecentAnalysis(): Flow<List<AnalysisHistoryEntity>>

    @Insert
    suspend fun insertAnalysisHistory(entry: AnalysisHistoryEntity)

    @Query("SELECT * FROM bookmarks WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC")
    fun observeBookmarks(projectId: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity)
}
