package org.bimz.reforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bimz.reforge.storage.SavedForgeFile
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.DarkForgeTokens
import org.bimz.reforge.ui.theme.ForgeAmber
import org.bimz.reforge.ui.theme.ForgeBg
import org.bimz.reforge.ui.theme.ForgeBorder
import org.bimz.reforge.ui.theme.ForgeColorTokens
import org.bimz.reforge.ui.theme.ForgeCyan
import org.bimz.reforge.ui.theme.ForgeElevated
import org.bimz.reforge.ui.theme.ForgeEmerald
import org.bimz.reforge.ui.theme.ForgeRose
import org.bimz.reforge.ui.theme.ForgeSurface
import org.bimz.reforge.ui.theme.ForgeTextMuted
import org.bimz.reforge.ui.theme.ForgeTextPrimary
import org.bimz.reforge.ui.theme.ForgeTextSecondary
import org.bimz.reforge.ui.theme.LocalForgeColors
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.roundToInt

private const val DUMP_CHUNK_LINES = 256
private const val MAX_LINE_CHARS = 512
private const val MAX_FILTERED_MATCHES = 25_000

private enum class DumpCategoryFilter(val label: String) {
    ALL("All"),
    STRINGS("Strings"),
    FUNCTIONS("Functions"),
    EXPORTS("Exports"),
    IMPORTS("Imports"),
    SECTIONS("Sections"),
    OFFSETS("Offsets (0x)")
}

private data class DumpLineEntry(
    val lineNumber: Int,
    val text: String
)

private class ChunkedDumpLineReader(
    private val file: File,
    private val lineOffsets: LongArray
) {
    private val cache = LruCache<Int, List<String>>(64)
    val totalLines: Int = lineOffsets.size.coerceAtLeast(1)

    fun getLine(lineIndex: Int): DumpLineEntry {
        if (!file.exists() || file.length() == 0L) {
            return DumpLineEntry(1, "<Empty file>")
        }
        val safeIdx = lineIndex.coerceIn(0, totalLines - 1)
        val blockIdx = safeIdx / DUMP_CHUNK_LINES
        val inBlockIdx = safeIdx % DUMP_CHUNK_LINES
        val block = getBlock(blockIdx)
        val text = block.getOrElse(inBlockIdx) { "" }
        return DumpLineEntry(safeIdx + 1, text)
    }

    fun readWindowText(startLineIndex: Int, count: Int): String {
        val safeStart = startLineIndex.coerceIn(0, totalLines - 1)
        val safeEnd = minOf(safeStart + count, totalLines)
        val sb = StringBuilder()
        for (i in safeStart until safeEnd) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(getLine(i).text)
        }
        return sb.toString()
    }

    private fun getBlock(blockIdx: Int): List<String> {
        cache.get(blockIdx)?.let { return it }
        val startLine = blockIdx * DUMP_CHUNK_LINES
        val endLine = minOf(startLine + DUMP_CHUNK_LINES, totalLines)
        if (startLine >= totalLines) return emptyList()

        val startByte = lineOffsets[startLine]
        val endByte = if (endLine < lineOffsets.size) lineOffsets[endLine] else file.length()
        val byteLen = (endByte - startByte).coerceIn(0L, 512L * 1024L).toInt()

        val lines = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(startByte)
                val data = ByteArray(byteLen)
                var readTotal = 0
                while (readTotal < byteLen) {
                    val r = raf.read(data, readTotal, byteLen - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                String(data, 0, readTotal, Charsets.UTF_8)
                    .split('\n')
                    .let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
                    .take(DUMP_CHUNK_LINES)
                    .map { raw ->
                        val clean = raw.trimEnd('\r')
                        if (clean.length > MAX_LINE_CHARS) {
                            clean.take(MAX_LINE_CHARS) + " … [truncated]"
                        } else {
                            clean
                        }
                    }
            }
        }.getOrDefault(emptyList())

        cache.put(blockIdx, lines)
        return lines
    }
}

private suspend fun indexLineOffsets(file: File): LongArray = withContext(Dispatchers.IO) {
    if (!file.exists() || file.length() == 0L) return@withContext longArrayOf(0L)
    val offsets = ArrayList<Long>(4096)
    offsets.add(0L)
    BufferedInputStream(FileInputStream(file), 64 * 1024).use { bis ->
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (true) {
            val n = bis.read(buf)
            if (n <= 0) break
            for (i in 0 until n) {
                if (buf[i] == '\n'.code.toByte()) {
                    val nextOffset = pos + i + 1L
                    if (nextOffset < file.length()) {
                        offsets.add(nextOffset)
                    }
                }
            }
            pos += n
        }
    }
    offsets.toLongArray()
}

private suspend fun filterDumpLinesStreaming(
    file: File,
    category: DumpCategoryFilter,
    query: String
): Pair<List<DumpLineEntry>, Boolean> = withContext(Dispatchers.IO) {
    if (!file.exists() || file.length() == 0L) return@withContext emptyList<DumpLineEntry>() to false
    val q = query.trim().lowercase(Locale.US)
    val matches = ArrayList<DumpLineEntry>(512)
    var truncated = false
    var activeSection = ""

    BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 64 * 1024).use { br ->
        var lineNo = 0
        while (true) {
            val rawLine = br.readLine() ?: break
            lineNo++
            val upper = rawLine.uppercase(Locale.US)
            if (upper.startsWith("[SECTIONS]") || upper.startsWith("## SECTIONS") || upper.contains("// SECTIONS")) activeSection = "SECTIONS"
            else if (upper.startsWith("[EXPORTS]") || upper.startsWith("## EXPORTS") || upper.contains("// EXPORTS")) activeSection = "EXPORTS"
            else if (upper.startsWith("[IMPORTS]") || upper.startsWith("## IMPORTS") || upper.contains("// IMPORTS")) activeSection = "IMPORTS"
            else if (upper.startsWith("[FUNCTIONS]") || upper.startsWith("## FUNCTIONS") || upper.contains("// FUNCTIONS")) activeSection = "FUNCTIONS"
            else if (upper.startsWith("[STRINGS]") || upper.startsWith("## STRINGS") || upper.contains("// STRINGS")) activeSection = "STRINGS"

            val catPass = when (category) {
                DumpCategoryFilter.ALL -> true
                DumpCategoryFilter.STRINGS -> activeSection == "STRINGS" || rawLine.contains("\"") || rawLine.contains("str.")
                DumpCategoryFilter.FUNCTIONS -> activeSection == "FUNCTIONS" || rawLine.contains("fcn.") || rawLine.contains("sym.") || rawLine.contains("size=")
                DumpCategoryFilter.EXPORTS -> activeSection == "EXPORTS" || upper.contains("EXPORT") || upper.contains("(FUNC)")
                DumpCategoryFilter.IMPORTS -> activeSection == "IMPORTS" || rawLine.contains("imp.") || upper.contains("IMPORT")
                DumpCategoryFilter.SECTIONS -> activeSection == "SECTIONS" || rawLine.contains("perm=") || rawLine.contains(".text") || rawLine.contains(".rodata") || rawLine.contains(".data")
                DumpCategoryFilter.OFFSETS -> rawLine.contains("0x", ignoreCase = true)
            }
            if (!catPass) continue

            if (q.isNotEmpty() && !rawLine.lowercase(Locale.US).contains(q)) continue

            val bounded = if (rawLine.length > MAX_LINE_CHARS) {
                rawLine.take(MAX_LINE_CHARS) + " … [truncated]"
            } else {
                rawLine
            }
            matches.add(DumpLineEntry(lineNo, bounded))
            if (matches.size >= MAX_FILTERED_MATCHES) {
                truncated = true
                break
            }
        }
    }
    matches to truncated
}

@Composable
fun DumpViewerOverlay(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val activeFile = state.activeDumpViewerFile
    val bubbleFile = state.dumpBubbleFile

    // 1. In-App Draggable Floating Dump Bubble (when reader is minimized/closed)
    if (activeFile == null && bubbleFile != null && bubbleFile.file.exists()) {
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 64.dp, end = 12.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = ForgeElevated,
                border = BorderStroke(1.2.dp, ForgeEmerald.copy(alpha = 0.75f)),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .clickable { viewModel.openDumpViewer(bubbleFile, compact = true) }
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(ForgeEmerald.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = "Open Dump",
                            tint = ForgeEmerald,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Column(modifier = Modifier.widthIn(max = 155.dp)) {
                        Text(
                            text = "DUMP READY",
                            color = ForgeEmerald,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = bubbleFile.name,
                            color = ForgeTextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { viewModel.dismissDumpBubble() },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Dismiss Bubble",
                            tint = ForgeTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }

    // 2. In-App Continuous-Scroll Dump Viewer Card
    if (activeFile != null) {
        InAppDumpReaderLayer(
            target = activeFile,
            compactMode = state.dumpViewerCompactMode,
            onToggleCompact = { viewModel.toggleDumpViewerCompactMode() },
            onMinimize = { viewModel.minimizeDumpViewerToBubble() },
            onClose = { viewModel.minimizeDumpViewerToBubble() }
        )
    }
}

@Composable
private fun InAppDumpReaderLayer(
    target: SavedForgeFile,
    compactMode: Boolean,
    onToggleCompact: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lineOffsets by remember(target) { mutableStateOf(longArrayOf(0L)) }
    var searchQuery by remember(target) { mutableStateOf("") }
    var selectedFilter by remember(target) { mutableStateOf(DumpCategoryFilter.ALL) }
    var filteredPool by remember(target) { mutableStateOf<Pair<List<DumpLineEntry>, Boolean>?>(null) }
    var isLoading by remember(target) { mutableStateOf(true) }
    var wrapLines by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }

    val viewerListState = rememberLazyListState()

    LaunchedEffect(target) {
        isLoading = true
        val offsets = runCatching { indexLineOffsets(target.file) }.getOrDefault(longArrayOf(0L))
        lineOffsets = offsets
        isLoading = false
    }

    val lineReader = remember(target, lineOffsets) {
        ChunkedDumpLineReader(target.file, lineOffsets)
    }

    LaunchedEffect(target, selectedFilter, searchQuery) {
        if (selectedFilter == DumpCategoryFilter.ALL && searchQuery.isBlank()) {
            filteredPool = null
        } else {
            if (searchQuery.isNotEmpty()) delay(180L)
            isLoading = true
            val res = runCatching {
                filterDumpLinesStreaming(target.file, selectedFilter, searchQuery)
            }.getOrDefault(emptyList<DumpLineEntry>() to false)
            filteredPool = res
            isLoading = false
        }
        viewerListState.scrollToItem(0)
    }

    val totalDisplayItems = filteredPool?.first?.size ?: lineReader.totalLines
    val truncatedMatches = filteredPool?.second == true
    val currentVisibleLine by remember(filteredPool, lineOffsets) {
        derivedStateOf {
            val idx = viewerListState.firstVisibleItemIndex
            val pool = filteredPool?.first
            if (pool != null) {
                pool.getOrNull(idx)?.lineNumber ?: (idx + 1)
            } else {
                (idx + 1).coerceIn(1, lineReader.totalLines)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .clickable { onMinimize() },
        contentAlignment = Alignment.Center
    ) {
        val cardModifier = if (compactMode) {
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.76f)
        } else {
            Modifier
                .fillMaxSize()
        }

        Surface(
            shape = if (compactMode) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp),
            color = ForgeBg,
            border = if (compactMode) BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.45f)) else null,
            shadowElevation = 16.dp,
            modifier = cardModifier.clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeSurface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .clickable(enabled = !isLoading && totalDisplayItems > 0) {
                                jumpInput = currentVisibleLine.toString()
                                showJumpDialog = true
                            }
                    ) {
                        Text(
                            text = target.name,
                            color = ForgeTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = if (!isLoading) {
                            val truncTag = if (truncatedMatches) "+" else ""
                            "${formatDumpBytes(target.sizeBytes)} • Ln $currentVisibleLine / $totalDisplayItems$truncTag ▾"
                        } else {
                            "${formatDumpBytes(target.sizeBytes)} • Indexing..."
                        }
                        Text(
                            text = sub,
                            color = ForgeTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ForgeElevated,
                        border = BorderStroke(1.dp, ForgeBorder),
                        modifier = Modifier.clickable { wrapLines = !wrapLines }
                    ) {
                        Text(
                            text = if (wrapLines) "WRAP" else "SCROLL",
                            color = ForgeCyan,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    IconButton(onClick = onToggleCompact, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (compactMode) Icons.Outlined.Fullscreen else Icons.Outlined.FullscreenExit,
                            contentDescription = "Toggle Size",
                            tint = ForgeCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        enabled = !isLoading && totalDisplayItems > 0,
                        onClick = {
                            val startIdx = viewerListState.firstVisibleItemIndex
                            val pool = filteredPool?.first
                            val copyChunk = if (pool != null) {
                                pool.drop(startIdx).take(300).joinToString("\n") { it.text }.take(200_000)
                            } else {
                                lineReader.readWindowText(startIdx, 300).take(200_000)
                            }
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText(target.name, copyChunk))
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Visible Lines", tint = ForgeTextSecondary, modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = {
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    target.file
                                )
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, target.name)
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, target.name))
                            }
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share", tint = ForgeEmerald, modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = onMinimize, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Minimize, contentDescription = "Minimize to Bubble", tint = ForgeAmber, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = ForgeRose, modifier = Modifier.size(16.dp))
                    }
                }

                // Search Bar + Category Filter Chips + Quick Top/Bottom Jump
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeBg)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForgeElevated,
                            border = BorderStroke(
                                1.dp,
                                if (searchQuery.isNotEmpty()) ForgeCyan.copy(alpha = 0.65f) else ForgeBorder
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(7.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search string, symbol, function, offset...",
                                            color = ForgeTextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = ForgeTextPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp
                                        ),
                                        cursorBrush = SolidColor(ForgeCyan),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Clear search",
                                        tint = ForgeTextSecondary,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable { searchQuery = "" }
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForgeElevated,
                            border = BorderStroke(1.dp, ForgeBorder),
                            modifier = Modifier.clickable(enabled = totalDisplayItems > 0) {
                                scope.launch { viewerListState.scrollToItem(0) }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = "Scroll to Top",
                                tint = ForgeCyan,
                                modifier = Modifier.padding(6.dp).size(16.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForgeElevated,
                            border = BorderStroke(1.dp, ForgeBorder),
                            modifier = Modifier.clickable(enabled = totalDisplayItems > 0) {
                                scope.launch { viewerListState.scrollToItem((totalDisplayItems - 1).coerceAtLeast(0)) }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Scroll to Bottom",
                                tint = ForgeCyan,
                                modifier = Modifier.padding(6.dp).size(16.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        DumpCategoryFilter.entries.forEach { filter ->
                            val active = filter == selectedFilter
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = if (active) ForgeCyan.copy(alpha = 0.18f) else ForgeSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (active) ForgeCyan.copy(alpha = 0.65f) else ForgeBorder
                                ),
                                modifier = Modifier.clickable { selectedFilter = filter }
                            ) {
                                Text(
                                    text = filter.label,
                                    color = if (active) ForgeCyan else ForgeTextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp)
                                )
                            }
                        }
                    }
                }

                // Continuous Scroll Content List + Fast Scrollbar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(ForgeBg)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    val pool = filteredPool?.first
                    val isEmptyResult = if (pool != null) pool.isEmpty() else lineReader.totalLines == 0

                    when {
                        isLoading -> {
                            Text(
                                text = "Scanning & indexing dump safely...",
                                color = ForgeCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        isEmptyResult -> {
                            Text(
                                text = "No matching lines found for '${selectedFilter.label}' and query '$searchQuery'.",
                                color = ForgeTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        else -> {
                            val forgeTokens = LocalForgeColors.current
                            SelectionContainer {
                                val listModifier = if (wrapLines) {
                                    Modifier
                                        .fillMaxSize()
                                        .padding(end = 16.dp)
                                } else {
                                    Modifier
                                        .fillMaxSize()
                                        .padding(end = 16.dp)
                                        .horizontalScroll(rememberScrollState())
                                }
                                LazyColumn(
                                    state = viewerListState,
                                    modifier = listModifier,
                                    contentPadding = PaddingValues(vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (pool != null) {
                                        items(pool, key = { it.lineNumber }) { entry ->
                                            DumpLineRow(
                                                entry = entry,
                                                searchQuery = searchQuery,
                                                wrapLines = wrapLines,
                                                tokens = forgeTokens
                                            )
                                        }
                                    } else {
                                        items(
                                            count = lineReader.totalLines,
                                            key = { it }
                                        ) { rowIndex ->
                                            val entry = remember(rowIndex, lineReader) {
                                                lineReader.getLine(rowIndex)
                                            }
                                            DumpLineRow(
                                                entry = entry,
                                                searchQuery = searchQuery,
                                                wrapLines = wrapLines,
                                                tokens = forgeTokens
                                            )
                                        }
                                    }
                                }
                            }

                            if (totalDisplayItems > 1) {
                                DumpFastScrollBar(
                                    totalItems = totalDisplayItems,
                                    firstVisibleItem = viewerListState.firstVisibleItemIndex,
                                    onScrubToItem = { targetIdx ->
                                        scope.launch {
                                            viewerListState.scrollToItem(targetIdx.coerceIn(0, totalDisplayItems - 1))
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJumpDialog) {
        val maxVal = totalDisplayItems.coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            containerColor = ForgeSurface,
            title = {
                Text(
                    text = if (filteredPool != null) "Jump to Match (1 - $maxVal)" else "Jump to Line (1 - $maxVal)",
                    color = ForgeTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = jumpInput,
                    onValueChange = { jumpInput = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Line number") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetLine = jumpInput.toIntOrNull()
                    if (targetLine != null && maxVal > 0) {
                        val targetIdx = (targetLine - 1).coerceIn(0, maxVal - 1)
                        scope.launch { viewerListState.scrollToItem(targetIdx) }
                    }
                    showJumpDialog = false
                }) {
                    Text("Go", color = ForgeCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text("Cancel", color = ForgeTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun DumpLineRow(
    entry: DumpLineEntry,
    searchQuery: String,
    wrapLines: Boolean,
    tokens: ForgeColorTokens
) {
    val styledLine = remember(entry.text, searchQuery, tokens) {
        styleDumpLineOverlay(entry.text, searchQuery, tokens)
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = String.format(Locale.US, "%5d │ ", entry.lineNumber),
            color = tokens.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.5.sp
        )
        Text(
            text = styledLine,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            lineHeight = 14.5.sp,
            softWrap = wrapLines
        )
    }
}

@Composable
private fun DumpFastScrollBar(
    totalItems: Int,
    firstVisibleItem: Int,
    onScrubToItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var trackHeightPx by remember { mutableIntStateOf(1) }
    val fraction = if (totalItems <= 1) 0f else (firstVisibleItem.toFloat() / (totalItems - 1)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
            .pointerInput(totalItems) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val y = change.position.y.coerceIn(0f, trackHeightPx.toFloat())
                    val ratio = y / trackHeightPx.toFloat()
                    onScrubToItem((ratio * (totalItems - 1)).toInt())
                }
            }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(ForgeBorder)
        )
        Column(modifier = Modifier.fillMaxHeight()) {
            if (fraction > 0.001f) {
                Spacer(modifier = Modifier.weight(fraction))
            }
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ForgeCyan)
            )
            if (1f - fraction > 0.001f) {
                Spacer(modifier = Modifier.weight(1f - fraction))
            }
        }
    }
}

private fun styleDumpLineOverlay(line: String, query: String, tokens: ForgeColorTokens = DarkForgeTokens): AnnotatedString {
    val baseColor = when {
        line.startsWith("[") && line.endsWith("]") -> tokens.cyan
        line.startsWith("//") || line.startsWith("#") || line.startsWith(";") || line.startsWith("=") -> tokens.emerald
        line.startsWith("0x") -> tokens.cyan
        else -> tokens.textPrimary
    }
    val q = query.trim()
    if (q.isEmpty()) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = baseColor)) { append(line) }
        }
    }
    val lowerLine = line.lowercase(Locale.US)
    val lowerQ = q.lowercase(Locale.US)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < line.length) {
            val idx = lowerLine.indexOf(lowerQ, cursor)
            if (idx < 0) {
                withStyle(SpanStyle(color = baseColor)) { append(line.substring(cursor)) }
                break
            }
            if (idx > cursor) {
                withStyle(SpanStyle(color = baseColor)) { append(line.substring(cursor, idx)) }
            }
            withStyle(
                SpanStyle(
                    color = tokens.bg,
                    background = tokens.amber,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(line.substring(idx, idx + q.length))
            }
            cursor = idx + q.length
        }
    }
}

private fun formatDumpBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
