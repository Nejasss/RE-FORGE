package org.bimz.reforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.NavigateBefore
import androidx.compose.material.icons.outlined.NavigateNext
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.withContext
import org.bimz.reforge.storage.SavedForgeFile
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.ForgeAmber
import org.bimz.reforge.ui.theme.ForgeBg
import org.bimz.reforge.ui.theme.DarkForgeTokens
import org.bimz.reforge.ui.theme.ForgeColorTokens
import org.bimz.reforge.ui.theme.LocalForgeColors
import org.bimz.reforge.ui.theme.ForgeBorder
import org.bimz.reforge.ui.theme.ForgeCyan
import org.bimz.reforge.ui.theme.ForgeElevated
import org.bimz.reforge.ui.theme.ForgeEmerald
import org.bimz.reforge.ui.theme.ForgeRose
import org.bimz.reforge.ui.theme.ForgeSurface
import org.bimz.reforge.ui.theme.ForgeTextMuted
import org.bimz.reforge.ui.theme.ForgeTextPrimary
import org.bimz.reforge.ui.theme.ForgeTextSecondary
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.roundToInt

private const val DUMP_PAGE_LINES = 250
private const val MAX_LINE_CHARS = 512
private const val MAX_FILTERED_MATCHES = 10_000

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

private data class PagedDumpSlice(
    val entries: List<DumpLineEntry>,
    val totalMatches: Int,
    val totalPages: Int,
    val currentPage: Int,
    val truncatedMatches: Boolean
)

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

private suspend fun readRawDumpPage(file: File, lineOffsets: LongArray, pageIndex: Int): PagedDumpSlice =
    withContext(Dispatchers.IO) {
        val totalLines = lineOffsets.size.coerceAtLeast(1)
        val totalPages = ((totalLines + DUMP_PAGE_LINES - 1) / DUMP_PAGE_LINES).coerceAtLeast(1)
        val safePage = pageIndex.coerceIn(0, totalPages - 1)
        val startLineIdx = safePage * DUMP_PAGE_LINES
        val endLineIdx = minOf(startLineIdx + DUMP_PAGE_LINES, totalLines)

        if (!file.exists() || file.length() == 0L) {
            return@withContext PagedDumpSlice(
                entries = listOf(DumpLineEntry(1, "<Empty file>")),
                totalMatches = 0,
                totalPages = 1,
                currentPage = 0,
                truncatedMatches = false
            )
        }

        val startByte = lineOffsets[startLineIdx]
        val endByte = if (endLineIdx < lineOffsets.size) lineOffsets[endLineIdx] else file.length()
        val byteLen = (endByte - startByte).coerceIn(0L, 512L * 1024L).toInt()

        val rawChunk = RandomAccessFile(file, "r").use { raf ->
            raf.seek(startByte)
            val data = ByteArray(byteLen)
            var readTotal = 0
            while (readTotal < byteLen) {
                val r = raf.read(data, readTotal, byteLen - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            String(data, 0, readTotal, Charsets.UTF_8)
        }

        val entries = rawChunk
            .split('\n')
            .let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            .take(DUMP_PAGE_LINES)
            .mapIndexed { idx, line ->
                val clean = line.trimEnd('\r')
                val bounded = if (clean.length > MAX_LINE_CHARS) {
                    clean.take(MAX_LINE_CHARS) + " … [truncated]"
                } else {
                    clean
                }
                DumpLineEntry(startLineIdx + idx + 1, bounded)
            }

        PagedDumpSlice(
            entries = entries,
            totalMatches = totalLines,
            totalPages = totalPages,
            currentPage = safePage,
            truncatedMatches = false
        )
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

    // 2. In-App Dump Viewer Card (Compact Modal ~76% height by default, or Fullscreen toggleable)
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
    var lineOffsets by remember(target) { mutableStateOf(longArrayOf(0L)) }
    var searchQuery by remember(target) { mutableStateOf("") }
    var selectedFilter by remember(target) { mutableStateOf(DumpCategoryFilter.ALL) }
    var currentPage by remember(target) { mutableIntStateOf(0) }
    var filteredPool by remember(target) { mutableStateOf<Pair<List<DumpLineEntry>, Boolean>?>(null) }
    var pageSlice by remember(target) { mutableStateOf<PagedDumpSlice?>(null) }
    var isLoading by remember(target) { mutableStateOf(true) }
    var wrapLines by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }

    val viewerListState = rememberLazyListState()

    LaunchedEffect(target) {
        isLoading = true
        val offsets = runCatching { indexLineOffsets(target.file) }.getOrDefault(longArrayOf(0L))
        lineOffsets = offsets
        pageSlice = runCatching { readRawDumpPage(target.file, offsets, 0) }.getOrNull()
        isLoading = false
    }

    LaunchedEffect(target, selectedFilter, searchQuery) {
        currentPage = 0
        if (selectedFilter == DumpCategoryFilter.ALL && searchQuery.isBlank()) {
            filteredPool = null
            if (lineOffsets.isNotEmpty()) {
                pageSlice = runCatching { readRawDumpPage(target.file, lineOffsets, 0) }.getOrNull()
            }
        } else {
            if (searchQuery.isNotEmpty()) delay(180L)
            isLoading = true
            val res = runCatching {
                filterDumpLinesStreaming(target.file, selectedFilter, searchQuery)
            }.getOrDefault(emptyList<DumpLineEntry>() to false)
            filteredPool = res
            val total = res.first.size
            val totalPages = ((total + DUMP_PAGE_LINES - 1) / DUMP_PAGE_LINES).coerceAtLeast(1)
            pageSlice = PagedDumpSlice(
                entries = res.first.take(DUMP_PAGE_LINES),
                totalMatches = total,
                totalPages = totalPages,
                currentPage = 0,
                truncatedMatches = res.second
            )
            isLoading = false
        }
    }

    LaunchedEffect(currentPage, filteredPool) {
        val pool = filteredPool
        if (pool == null) {
            if (!isLoading) {
                pageSlice = runCatching { readRawDumpPage(target.file, lineOffsets, currentPage) }.getOrNull()
            }
        } else {
            val total = pool.first.size
            val totalPages = ((total + DUMP_PAGE_LINES - 1) / DUMP_PAGE_LINES).coerceAtLeast(1)
            val safePage = currentPage.coerceIn(0, totalPages - 1)
            val fromIdx = safePage * DUMP_PAGE_LINES
            val toIdx = minOf(fromIdx + DUMP_PAGE_LINES, total)
            pageSlice = PagedDumpSlice(
                entries = if (fromIdx < toIdx) pool.first.subList(fromIdx, toIdx) else emptyList(),
                totalMatches = total,
                totalPages = totalPages,
                currentPage = safePage,
                truncatedMatches = pool.second
            )
        }
        viewerListState.scrollToItem(0)
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
                    ) {
                        Text(
                            text = target.name,
                            color = ForgeTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val s = pageSlice
                        val sub = if (s != null) {
                            val truncTag = if (s.truncatedMatches) "+" else ""
                            "${formatDumpBytes(target.sizeBytes)} • ${s.totalMatches}$truncTag lines • Pg ${s.currentPage + 1}/${s.totalPages}"
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
                        enabled = pageSlice != null,
                        onClick = {
                            val pageText = pageSlice?.entries?.joinToString("\n") { it.text }.orEmpty().take(200_000)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText(target.name, pageText))
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Page", tint = ForgeTextSecondary, modifier = Modifier.size(15.dp))
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

                // Search Bar + Category Filter Chips
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeBg)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ForgeElevated,
                        border = BorderStroke(
                            1.dp,
                            if (searchQuery.isNotEmpty()) ForgeCyan.copy(alpha = 0.65f) else ForgeBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
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
                                        text = "Search string, symbol, function, offset (0x...)...",
                                        color = ForgeTextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
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

                // Content List
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF06090E))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    val slice = pageSlice
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
                        slice == null || slice.entries.isEmpty() -> {
                            Text(
                                text = "No matching lines found for '${selectedFilter.label}' and query '$searchQuery'.",
                                color = ForgeTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        else -> {
                            SelectionContainer {
                                val listModifier = if (wrapLines) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier
                                        .fillMaxSize()
                                        .horizontalScroll(rememberScrollState())
                                }
                                LazyColumn(
                                    state = viewerListState,
                                    modifier = listModifier,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(slice.entries, key = { it.lineNumber }) { entry ->
                                        val forgeTokens = LocalForgeColors.current
                                        val styledLine = remember(entry.text, searchQuery, forgeTokens) {
                                            styleDumpLineOverlay(entry.text, searchQuery, forgeTokens)
                                        }
                                        Row(verticalAlignment = Alignment.Top) {
                                            Text(
                                                text = String.format(Locale.US, "%5d │ ", entry.lineNumber),
                                                color = ForgeTextMuted,
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
                                }
                            }
                        }
                    }
                }

                // Guaranteed Visible Pagination Controls Bar
                val slice = pageSlice
                if (slice != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ForgeSurface)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { currentPage = 0 },
                            enabled = slice.currentPage > 0,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.FirstPage,
                                contentDescription = "First Page",
                                tint = if (slice.currentPage > 0) ForgeCyan else ForgeTextMuted
                            )
                        }
                        IconButton(
                            onClick = { if (currentPage > 0) currentPage -= 1 },
                            enabled = slice.currentPage > 0,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.NavigateBefore,
                                contentDescription = "Previous Page",
                                tint = if (slice.currentPage > 0) ForgeCyan else ForgeTextMuted
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForgeElevated,
                            border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.45f)),
                            modifier = Modifier.clickable {
                                jumpInput = (slice.currentPage + 1).toString()
                                showJumpDialog = true
                            }
                        ) {
                            Text(
                                text = "Page ${slice.currentPage + 1} / ${slice.totalPages} ▾",
                                color = ForgeTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                        IconButton(
                            onClick = { if (currentPage < slice.totalPages - 1) currentPage += 1 },
                            enabled = slice.currentPage < slice.totalPages - 1,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.NavigateNext,
                                contentDescription = "Next Page",
                                tint = if (slice.currentPage < slice.totalPages - 1) ForgeCyan else ForgeTextMuted
                            )
                        }
                        IconButton(
                            onClick = { currentPage = slice.totalPages - 1 },
                            enabled = slice.currentPage < slice.totalPages - 1,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.LastPage,
                                contentDescription = "Last Page",
                                tint = if (slice.currentPage < slice.totalPages - 1) ForgeCyan else ForgeTextMuted
                            )
                        }
                    }
                }
            }
        }
    }

    val totalPg = pageSlice?.totalPages ?: 1
    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            containerColor = ForgeSurface,
            title = {
                Text("Jump to Page (1 - $totalPg)", color = ForgeTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = jumpInput,
                    onValueChange = { jumpInput = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Page number") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetPage = jumpInput.toIntOrNull()
                    if (targetPage != null && totalPg > 0) {
                        currentPage = (targetPage - 1).coerceIn(0, totalPg - 1)
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
