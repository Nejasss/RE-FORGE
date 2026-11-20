package org.bimz.reforge.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, AnalysisHistoryEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        @Volatile
        private var instance: WorkspaceDatabase? = null

        fun getInstance(context: Context): WorkspaceDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkspaceDatabase::class.java,
                    "reforge_workspace.db"
                ).build().also { instance = it }
            }
        }
    }
}
