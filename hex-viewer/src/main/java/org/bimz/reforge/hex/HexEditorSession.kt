package org.bimz.reforge.hex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.binary.NativeBinaryBridge
import java.io.File
import java.io.RandomAccessFile

data class HexPatchOperation(
    val offset: Long,
    val originalBytes: ByteArray,
    val patchedBytes: ByteArray
)

class HexEditorSession(private val workingFile: File) {

    private val undoStack = ArrayDeque<HexPatchOperation>()
    private val redoStack = ArrayDeque<HexPatchOperation>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val patchHistory: List<HexPatchOperation> get() = undoStack.toList()

    suspend fun readPage(offset: Long, length: Int = 512): ByteArray = withContext(Dispatchers.IO) {
        if (!workingFile.exists() || offset < 0L || offset >= workingFile.length()) {
            return@withContext ByteArray(0)
        }
        if (NativeBinaryBridge.isNativeLoaded) {
            return@withContext NativeBinaryBridge.nativeReadSlice(workingFile.absolutePath, offset, length)
        }
        RandomAccessFile(workingFile, "r").use { raf ->
            raf.seek(offset)
            val safeLen = minOf(length.toLong(), workingFile.length() - offset).toInt()
            val buf = ByteArray(safeLen)
            val read = raf.read(buf)
            if (read > 0) buf.copyOf(read) else ByteArray(0)
        }
    }

    suspend fun searchHexPattern(startOffset: Long, hexQuery: String): Long = withContext(Dispatchers.IO) {
        val pattern = parseHexString(hexQuery)
        if (pattern.isEmpty() || !workingFile.exists()) return@withContext -1L
        if (NativeBinaryBridge.isNativeLoaded) {
            return@withContext NativeBinaryBridge.nativeSearchBytes(workingFile.absolutePath, startOffset, pattern)
        }
        val data = workingFile.readBytes()
        val start = startOffset.toInt().coerceAtLeast(0)
        for (i in start..(data.size - pattern.size)) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return@withContext i.toLong()
        }
        -1L
    }

    suspend fun applyPatch(offset: Long, hexReplacement: String): Result<HexPatchOperation> =
        withContext(Dispatchers.IO) {
            runCatching {
                val replacement = parseHexString(hexReplacement)
                require(replacement.isNotEmpty()) { "Hex replacement cannot be empty" }
                require(offset >= 0 && offset + replacement.size <= workingFile.length()) {
                    "Patch exceeds file boundaries (offset=0x${offset.toString(16)}, fileSize=${workingFile.length()})"
                }
                val original = readPage(offset, replacement.size)
                writeBytesAt(offset, replacement)
                val op = HexPatchOperation(offset, original, replacement)
                undoStack.addLast(op)
                redoStack.clear()
                op
            }
        }

    suspend fun undo(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val op = undoStack.removeLastOrNull() ?: return@runCatching false
            writeBytesAt(op.offset, op.originalBytes)
            redoStack.addLast(op)
            true
        }
    }

    suspend fun redo(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val op = redoStack.removeLastOrNull() ?: return@runCatching false
            writeBytesAt(op.offset, op.patchedBytes)
            undoStack.addLast(op)
            true
        }
    }

    private fun writeBytesAt(offset: Long, bytes: ByteArray) {
        if (NativeBinaryBridge.isNativeLoaded) {
            val ok = NativeBinaryBridge.nativePatchBytes(workingFile.absolutePath, offset, bytes)
            check(ok) { "Native pwrite64 patch failed at offset 0x${offset.toString(16)}" }
            return
        }
        RandomAccessFile(workingFile, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
    }

    companion object {
        fun parseHexString(raw: String): ByteArray {
            val clean = raw.replace(Regex("[^0-9A-Fa-f]"), "")
            require(clean.length % 2 == 0) { "Hex input must have an even number of nibbles" }
            return ByteArray(clean.length / 2) { idx ->
                clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
