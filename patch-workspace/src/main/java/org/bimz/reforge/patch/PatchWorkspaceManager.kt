package org.bimz.reforge.patch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.ChangeType
import org.bimz.reforge.core.model.ModifiedEntryRecord
import org.bimz.reforge.core.model.WorkspaceProject
import java.io.File

class PatchWorkspaceManager {

    fun getStagingRoot(project: WorkspaceProject): File =
        File(project.workspaceDir, "staging").apply { mkdirs() }

    fun getSmaliStagingDir(project: WorkspaceProject): File =
        File(getStagingRoot(project), "smali").apply { mkdirs() }

    fun getOverlayFilesDir(project: WorkspaceProject): File =
        File(getStagingRoot(project), "overlay").apply { mkdirs() }

    suspend fun saveSmaliClassEdit(
        project: WorkspaceProject,
        classDescriptor: String,
        editedSmali: String
    ): File = withContext(Dispatchers.IO) {
        val relPath = classDescriptor.removePrefix("L").removeSuffix(";") + ".smali"
        val target = File(getSmaliStagingDir(project), relPath)
        target.parentFile?.mkdirs()
        target.writeText(editedSmali, Charsets.UTF_8)
        target
    }

    suspend fun saveOverlayEntryEdit(
        project: WorkspaceProject,
        entryRelativePath: String,
        content: ByteArray
    ): File = withContext(Dispatchers.IO) {
        val sanitized = entryRelativePath.trimStart('/')
        require(!sanitized.contains("..")) { "Invalid overlay path traversal: $entryRelativePath" }
        val target = File(getOverlayFilesDir(project), sanitized)
        target.parentFile?.mkdirs()
        target.writeBytes(content)
        target
    }

    suspend fun listModifiedEntries(project: WorkspaceProject): List<ModifiedEntryRecord> =
        withContext(Dispatchers.IO) {
            val records = mutableListOf<ModifiedEntryRecord>()
            val staging = getStagingRoot(project)
            if (staging.exists()) {
                staging.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(staging).path
                    records.add(
                        ModifiedEntryRecord(
                            relativePath = rel,
                            changeType = ChangeType.MODIFIED,
                            originalSize = 0L,
                            modifiedSize = file.length(),
                            timestampEpochMs = file.lastModified(),
                            validationState = "STAGED"
                        )
                    )
                }
            }
            val orig = File(project.originalPath)
            val work = File(project.workingPath)
            if (orig.exists() && work.exists() && orig.length() != work.length()) {
                records.add(
                    ModifiedEntryRecord(
                        relativePath = work.name,
                        changeType = ChangeType.MODIFIED,
                        originalSize = orig.length(),
                        modifiedSize = work.length(),
                        timestampEpochMs = work.lastModified(),
                        validationState = "WORKING_COPY_MODIFIED"
                    )
                )
            }
            records
        }

    fun computeUnifiedLineDiff(originalText: String, modifiedText: String, fileLabel: String): String {
        val origLines = originalText.lines()
        val modLines = modifiedText.lines()
        val sb = StringBuilder()
        sb.appendLine("--- a/$fileLabel")
        sb.appendLine("+++ b/$fileLabel")
        val maxLines = maxOf(origLines.size, modLines.size)
        var changes = 0
        for (i in 0 until maxLines) {
            val a = origLines.getOrNull(i)
            val b = modLines.getOrNull(i)
            if (a != b) {
                changes++
                if (a != null) sb.appendLine("-${i + 1}: $a")
                if (b != null) sb.appendLine("+${i + 1}: $b")
            }
        }
        if (changes == 0) {
            sb.appendLine("No line differences detected.")
        }
        return sb.toString()
    }
}
