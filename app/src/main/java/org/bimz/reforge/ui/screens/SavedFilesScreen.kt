package org.bimz.reforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material.icons.outlined.NavigateBefore
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.bimz.reforge.storage.SavedForgeFile
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.ForgeAmber
import org.bimz.reforge.ui.theme.ForgeBg
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            if (upper.startsWith("[SECTIONS]") || upper.startsWith("## SECTIONS")) activeSection = "SECTIONS"
            else if (upper.startsWith("[EXPORTS]") || upper.startsWith("## EXPORTS")) activeSection = "EXPORTS"
            else if (upper.startsWith("[IMPORTS]") || upper.startsWith("## IMPORTS")) activeSection = "IMPORTS"
            else if (upper.startsWith("[FUNCTIONS]") || upper.startsWith("## FUNCTIONS")) activeSection = "FUNCTIONS"
            else if (upper.startsWith("[STRINGS]") || upper.startsWith("## STRINGS")) activeSection = "STRINGS"

            val catPass = when (category) {
                DumpCategoryFilter.ALL -> true
                DumpCategoryFilter.STRINGS -> activeSection == "STRINGS" || rawLine.contains("\"") || rawLine.contains("str.")
                DumpCategoryFilter.FUNCTIONS -> activeSection == "FUNCTIONS" || rawLine.contains("fcn.") || rawLine.contains("sym.") || rawLine.contains("size=")
                DumpCategoryFilter.EXPORTS -> activeSection == "EXPORTS" || upper.contains("EXPORT") || upper.contains("FUNC")
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
fun SavedFilesScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToHex: () -> Unit = {}
) {
    val context = LocalContext.current
    val bottomClearance = LocalBottomClearance.current
    var previewFile by remember { mutableStateOf<SavedForgeFile?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFileDirectToForge(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSavedForgeFiles(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = ForgeSurface,
            border = BorderStroke(1.dp, ForgeBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderSpecial,
                    contentDescription = null,
                    tint = ForgeCyan,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RE FORGE Vault",
                        color = ForgeTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Download/RE FORGE • ${state.savedForgeFiles.size} file(s)",
                        color = ForgeTextSecondary,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForgeCyan,
                        contentColor = ForgeBg
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.savedForgeFiles.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = ForgeSurface,
                border = BorderStroke(1.dp, ForgeBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Folder RE FORGE masih kosong",
                        color = ForgeTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Hasil 'dump' / 'Save to RE FORGE' dari terminal Radare2 maupun binary yang di-import akan tersimpan otomatis di sini dan bisa langsung dibuka.",
                        color = ForgeTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = bottomClearance + 20.dp)
            ) {
                items(state.savedForgeFiles, key = { it.file.absolutePath }) { item ->
                    SavedForgeFileCard(
                        item = item,
                        onReadDump = { previewFile = item },
                        onOpenTerminal = {
                            viewModel.openSavedForgeFileInWorkstation(item.file)
                            onNavigateToTerminal()
                        },
                        onOpenHex = {
                            viewModel.openSavedForgeFileInWorkstation(item.file)
                            onNavigateToHex()
                        },
                        onDelete = { viewModel.deleteSavedForgeFile(context, item) }
                    )
                }
            }
        }
    }

    val activePreview = previewFile
    if (activePreview != null) {
        FullScreenDumpViewerDialog(
            target = activePreview,
            onDismiss = { previewFile = null }
        )
    }
}

@Composable
private fun FullScreenDumpViewerDialog(
    target: SavedForgeFile,
    onDismiss: () -> Unit
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ForgeBg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Full-Screen Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = ForgeCyan
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.name,
                        color = ForgeTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val s = pageSlice
                    val sub = if (s != null) {
                        val truncTag = if (s.truncatedMatches) "+" else ""
                        "${formatFileBytes(target.sizeBytes)} • ${s.totalMatches}$truncTag lines • Page ${s.currentPage + 1}/${s.totalPages}"
                    } else {
                        "${formatFileBytes(target.sizeBytes)} • Loading..."
                    }
                    Text(
                        text = sub,
                        color = ForgeTextSecondary,
                        fontSize = 10.5.sp,
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
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    enabled = pageSlice != null,
                    onClick = {
                        val pageText = pageSlice?.entries?.joinToString("\n") { it.text }.orEmpty().take(200_000)
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText(target.name, pageText))
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Page", tint = ForgeCyan, modifier = Modifier.size(18.dp))
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
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share File", tint = ForgeEmerald, modifier = Modifier.size(18.dp))
                }
            }

            // Search Bar + Category Filter Chips
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1018))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search string, symbol, function, offset (0x...)...",
                                    color = ForgeTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = ForgeTextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
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
                                    .size(16.dp)
                                    .clickable { searchQuery = "" }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                fontSize = 10.5.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Full-Screen Content Canvas
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
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    slice == null || slice.entries.isEmpty() -> {
                        Text(
                            text = "No matching lines found for filter '${selectedFilter.label}' and query '$searchQuery'.",
                            color = ForgeTextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
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
                                    val styledLine = remember(entry.text, searchQuery) {
                                        styleDumpLine(entry.text, searchQuery)
                                    }
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = String.format(Locale.US, "%5d │ ", entry.lineNumber),
                                            color = ForgeTextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            lineHeight = 15.sp
                                        )
                                        Text(
                                            text = styledLine,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            softWrap = wrapLines
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Pagination Bar
            val slice = pageSlice
            if (slice != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeSurface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { currentPage = 0 },
                        enabled = slice.currentPage > 0,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.FirstPage, contentDescription = "First", tint = ForgeCyan)
                    }
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage -= 1 },
                        enabled = slice.currentPage > 0,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.NavigateBefore, contentDescription = "Prev", tint = ForgeCyan)
                    }
                    Text(
                        text = "Page ${slice.currentPage + 1} / ${slice.totalPages}",
                        color = ForgeTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { if (currentPage < slice.totalPages - 1) currentPage += 1 },
                        enabled = slice.currentPage < slice.totalPages - 1,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.NavigateNext, contentDescription = "Next", tint = ForgeCyan)
                    }
                    IconButton(
                        onClick = { currentPage = slice.totalPages - 1 },
                        enabled = slice.currentPage < slice.totalPages - 1,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.LastPage, contentDescription = "Last", tint = ForgeCyan)
                    }
                }
            }
        }
    }
}

private fun styleDumpLine(line: String, query: String): AnnotatedString {
    val baseColor = when {
        line.startsWith("[") && line.endsWith("]") -> ForgeCyan
        line.startsWith("//") || line.startsWith("#") || line.startsWith(";") || line.startsWith("=") -> ForgeEmerald
        line.startsWith("0x") -> ForgeCyan
        else -> ForgeTextPrimary
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
                    color = ForgeBg,
                    background = ForgeAmber,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(line.substring(idx, idx + q.length))
            }
            cursor = idx + q.length
        }
    }
}

@Composable
private fun SavedForgeFileCard(
    item: SavedForgeFile,
    onReadDump: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenHex: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.US) }
    val badgeColor = if (item.isTextDump) ForgeEmerald else ForgeCyan
    val badgeText = if (item.isTextDump) "DUMP" else "BINARY"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ForgeSurface,
        border = BorderStroke(1.dp, ForgeBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.name,
                    color = ForgeTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        tint = ForgeRose,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatFileBytes(item.sizeBytes)} • ${dateFmt.format(Date(item.lastModifiedMs))}",
                color = ForgeTextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.isTextDump) {
                    Button(
                        onClick = onReadDump,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForgeEmerald,
                            contentColor = ForgeBg
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Open Dump", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenHex,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeTextPrimary),
                        border = BorderStroke(1.dp, ForgeBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.GridOn, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Hex", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onOpenTerminal,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForgeCyan,
                            contentColor = ForgeBg
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Open in r2", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenHex,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeTextPrimary),
                        border = BorderStroke(1.dp, ForgeBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.GridOn, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Open in Hex", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatFileBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
