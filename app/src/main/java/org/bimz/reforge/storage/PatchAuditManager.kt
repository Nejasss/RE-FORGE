package org.bimz.reforge.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PatchAuditManager {

    private const val PREFS_NAME = "reforge_patch_audit"
    private const val MAX_BACKUPS = 25

    fun forgeRoot(context: Context): File =
        File(context.filesDir, "RE FORGE").apply { mkdirs() }

    fun backupDir(context: Context): File =
        File(forgeRoot(context), "backups").apply { mkdirs() }

    fun patchLogFile(context: Context): File =
        File(forgeRoot(context), "patch_history.log")

    fun backupBeforePatch(context: Context, targetFile: File): File? {
        if (!targetFile.exists() || !targetFile.isFile) return null
        return runCatching {
            val dir = backupDir(context)
            val sha = sha256(targetFile)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingPath = prefs.getString("bak_$sha", null)
            if (!existingPath.isNullOrBlank()) {
                val existingFile = File(existingPath)
                if (existingFile.exists() && existingFile.length() == targetFile.length()) {
                    return@runCatching existingFile
                }
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeName = targetFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val bakFile = File(dir, "${safeName}_${stamp}.bak")
            targetFile.inputStream().use { input ->
                bakFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putString("bak_$sha", bakFile.absolutePath).apply()

            val allBaks = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            if (allBaks.size > MAX_BACKUPS) {
                allBaks.drop(MAX_BACKUPS).forEach { old -> old.delete() }
            }
            bakFile
        }.getOrNull()
    }

    fun recordPatchEvent(
        context: Context,
        source: String,
        targetFile: File,
        addressLabel: String,
        detail: String,
        backupFile: File?
    ) {
        runCatching {
            val logFile = patchLogFile(context)
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val bakLabel = backupFile?.name ?: "none"
            val line = "[$dateStr] [$source] ${targetFile.name} @ $addressLabel | $detail | backup: $bakLabel\n"
            logFile.appendText(line, Charsets.UTF_8)
        }
    }

    fun readPatchHistory(context: Context, maxEntries: Int = 150): String {
        val logFile = patchLogFile(context)
        if (!logFile.exists()) return ""
        val lines = runCatching { logFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        return lines.takeLast(maxEntries).asReversed().joinToString("\n")
    }

    fun clearPatchHistory(context: Context) {
        runCatching {
            val logFile = patchLogFile(context)
            if (logFile.exists()) logFile.delete()
        }
    }

    fun getCacheSizeBytes(context: Context): Long {
        var total = 0L
        val r2Projects = File(context.filesDir, "r2home/.local/share/radare2/projects")
        val r2Tmp = File(context.cacheDir, "r2tmp")
        total += dirSize(r2Projects)
        total += dirSize(r2Tmp)
        context.cacheDir.listFiles()?.forEach { child ->
            if (child.isFile) total += child.length()
        }
        return total
    }

    fun clearAllCaches(context: Context): Long {
        val before = getCacheSizeBytes(context)
        val r2Projects = File(context.filesDir, "r2home/.local/share/radare2/projects")
        val r2Tmp = File(context.cacheDir, "r2tmp")
        deleteRecursivelySafe(r2Projects)
        deleteRecursivelySafe(r2Tmp)
        context.cacheDir.listFiles()?.forEach { child ->
            if (child.isFile) child.delete()
        }
        context.getSharedPreferences("r2_aaa_cache", Context.MODE_PRIVATE).edit().clear().apply()
        val after = getCacheSizeBytes(context)
        return (before - after).coerceAtLeast(0L)
    }

    fun getBackupStats(context: Context): Pair<Int, Long> {
        val files = backupDir(context).listFiles()?.filter { it.isFile }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clearBackups(context: Context): Long {
        val files = backupDir(context).listFiles()?.filter { it.isFile }.orEmpty()
        val freed = files.sumOf { it.length() }
        files.forEach { it.delete() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        return freed
    }

    private fun dirSize(node: File): Long {
        if (!node.exists()) return 0L
        if (node.isFile) return node.length()
        return node.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun deleteRecursivelySafe(node: File) {
        if (!node.exists()) return
        if (node.isDirectory) {
            node.listFiles()?.forEach { deleteRecursivelySafe(it) }
        }
        node.delete()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16384)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
