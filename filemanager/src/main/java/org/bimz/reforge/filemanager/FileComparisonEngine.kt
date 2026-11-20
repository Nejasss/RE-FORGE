package org.bimz.reforge.filemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.security.HashCalculator
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

data class BinaryDiffChunk(
    val offset: Long,
    val length: Int,
    val bytesA: String,
    val bytesB: String
)

data class FileComparisonReport(
    val fileAName: String,
    val fileBName: String,
    val sizeA: Long,
    val sizeB: Long,
    val sha256A: String,
    val sha256B: String,
    val identicalHash: Boolean,
    val addedZipEntries: List<String>,
    val removedZipEntries: List<String>,
    val modifiedZipEntries: List<String>,
    val diffChunks: List<BinaryDiffChunk>,
    val totalDifferingBytes: Long
)

class FileComparisonEngine {

    suspend fun compare(fileA: File, fileB: File, maxDiffChunks: Int = 64): FileComparisonReport =
        withContext(Dispatchers.IO) {
            val hashA = HashCalculator.computeReport(fileA)
            val hashB = HashCalculator.computeReport(fileB)

            val addedEntries = mutableListOf<String>()
            val removedEntries = mutableListOf<String>()
            val modifiedEntries = mutableListOf<String>()

            if (isZipFile(fileA) && isZipFile(fileB)) {
                val mapA = readZipInventory(fileA)
                val mapB = readZipInventory(fileB)
                for ((name, metaB) in mapB) {
                    val metaA = mapA[name]
                    if (metaA == null) {
                        addedEntries.add(name)
                    } else if (metaA != metaB) {
                        modifiedEntries.add(name)
                    }
                }
                for (name in mapA.keys) {
                    if (!mapB.containsKey(name)) {
                        removedEntries.add(name)
                    }
                }
            }

            val chunks = mutableListOf<BinaryDiffChunk>()
            var totalDiffBytes = 0L
            val bufA = ByteArray(64 * 1024)
            val bufB = ByteArray(64 * 1024)

            FileInputStream(fileA).use { inA ->
                FileInputStream(fileB).use { inB ->
                    var offset = 0L
                    while (true) {
                        val readA = inA.read(bufA)
                        val readB = inB.read(bufB)
                        if (readA <= 0 && readB <= 0) break
                        val minRead = maxOf(0, minOf(readA, readB))
                        var i = 0
                        while (i < minRead) {
                            if (bufA[i] != bufB[i]) {
                                val start = i
                                while (i < minRead && bufA[i] != bufB[i] && (i - start) < 16) {
                                    i++
                                }
                                val span = i - start
                                totalDiffBytes += span
                                if (chunks.size < maxDiffChunks) {
                                    val hexA = (start until i).joinToString(" ") { "%02X".format(bufA[it]) }
                                    val hexB = (start until i).joinToString(" ") { "%02X".format(bufB[it]) }
                                    chunks.add(BinaryDiffChunk(offset + start, span, hexA, hexB))
                                }
                            } else {
                                i++
                            }
                        }
                        val tailDiff = kotlin.math.abs(maxOf(0, readA) - maxOf(0, readB))
                        totalDiffBytes += tailDiff
                        offset += maxOf(readA, readB).toLong()
                    }
                }
            }

            FileComparisonReport(
                fileAName = fileA.name,
                fileBName = fileB.name,
                sizeA = hashA.fileSize,
                sizeB = hashB.fileSize,
                sha256A = hashA.sha256,
                sha256B = hashB.sha256,
                identicalHash = hashA.sha256 == hashB.sha256,
                addedZipEntries = addedEntries.sorted(),
                removedZipEntries = removedEntries.sorted(),
                modifiedZipEntries = modifiedEntries.sorted(),
                diffChunks = chunks,
                totalDifferingBytes = totalDiffBytes
            )
        }

    private fun isZipFile(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { ins ->
            val hdr = ByteArray(4)
            ins.read(hdr) == 4 &&
                hdr[0] == 'P'.code.toByte() &&
                hdr[1] == 'K'.code.toByte() &&
                hdr[2] == 0x03.toByte() &&
                hdr[3] == 0x04.toByte()
        }
    }

    private fun readZipInventory(file: File): Map<String, Pair<Long, Long>> {
        val result = mutableMapOf<String, Pair<Long, Long>>()
        runCatching {
            ZipFile(file).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory) {
                        result[e.name] = e.size to e.crc
                    }
                }
            }
        }
        return result
    }
}
