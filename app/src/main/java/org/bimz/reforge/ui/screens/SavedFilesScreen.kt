package org.bimz.reforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material.icons.outlined.NavigateBefore
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.storage.SavedForgeFile
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.ForgeBg
import org.bimz.reforge.ui.theme.ForgeBorder
import org.bimz.reforge.ui.theme.ForgeCyan
import org.bimz.reforge.ui.theme.ForgeEmerald
import org.bimz.reforge.ui.theme.ForgeRose
import org.bimz.reforge.ui.theme.ForgeSurface
import org.bimz.reforge.ui.theme.ForgeTextMuted
import org.bimz.reforge.ui.theme.ForgeTextPrimary
import org.bimz.reforge.ui.theme.ForgeTextSecondary
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DUMP_PAGE_LINES = 250
private const val MAX_LINE_CHARS = 512

private data class PagedDumpSlice(
    val lines: List<String>,
    val startLineNumber: Int,
    val totalLines: Int,
    val totalPages: Int,
    val currentPage: Int
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

private suspend fun readDumpPage(file: File, lineOffsets: LongArray, pageIndex: Int): PagedDumpSlice =
    withContext(Dispatchers.IO) {
        val totalLines = lineOffsets.size.coerceAtLeast(1)
        val totalPages = ((totalLines + DUMP_PAGE_LINES - 1) / DUMP_PAGE_LINES).coerceAtLeast(1)
        val safePage = pageIndex.coerceIn(0, totalPages - 1)
        val startLineIdx = safePage * DUMP_PAGE_LINES
        val endLineIdx = minOf(startLineIdx + DUMP_PAGE_LINES, totalLines)

        if (!file.exists() || file.length() == 0L) {
            return@withContext PagedDumpSlice(
                lines = listOf("<Empty file>"),
                startLineNumber = 1,
                totalLines = 0,
                totalPages = 1,
                currentPage = 0
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

        val slicedLines = rawChunk
            .split('\n')
            .let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            .take(DUMP_PAGE_LINES)
            .map { line ->
                val clean = line.trimEnd('\r')
                if (clean.length > MAX_LINE_CHARS) {
                    clean.take(MAX_LINE_CHARS) + " … [truncated ${clean.length - MAX_LINE_CHARS} chars]"
                } else {
                    clean
                }
            }

        PagedDumpSlice(
            lines = slicedLines,
            startLineNumber = startLineIdx + 1,
            totalLines = totalLines,
            totalPages = totalPages,
            currentPage = safePage
        )
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
    var lineOffsets by remember { mutableStateOf(longArrayOf(0L)) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageSlice by remember { mutableStateOf<PagedDumpSlice?>(null) }
    var isIndexing by remember { mutableStateOf(false) }

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

    LaunchedEffect(previewFile) {
        val target = previewFile
        if (target != null) {
            isIndexing = true
            pageSlice = null
            currentPage = 0
            val offsets = runCatching { indexLineOffsets(target.file) }.getOrDefault(longArrayOf(0L))
            lineOffsets = offsets
            pageSlice = runCatching { readDumpPage(target.file, offsets, 0) }.getOrNull()
            isIndexing = false
        } else {
            pageSlice = null
        }
    }

    LaunchedEffect(previewFile, currentPage) {
        val target = previewFile ?: return@LaunchedEffect
        if (!isIndexing) {
            pageSlice = runCatching { readDumpPage(target.file, lineOffsets, currentPage) }.getOrNull()
        }
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
        val slice = pageSlice
        val viewerListState = rememberLazyListState()
        LaunchedEffect(currentPage) {
            viewerListState.scrollToItem(0)
        }

        AlertDialog(
            onDismissRequest = { previewFile = null },
            containerColor = ForgeSurface,
            title = {
                Column {
                    Text(
                        text = activePreview.name,
                        color = ForgeTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaLabel = if (slice != null) {
                        "${formatFileBytes(activePreview.sizeBytes)} • Page ${slice.currentPage + 1}/${slice.totalPages} (${slice.totalLines} lines)"
                    } else {
                        "${formatFileBytes(activePreview.sizeBytes)} • Indexing lines..."
                    }
                    Text(
                        text = metaLabel,
                        color = ForgeTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(330.dp)
                            .background(ForgeBg, RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        if (isIndexing || slice == null) {
                            Text(
                                text = "Loading chunk safely (anti-OOM paged reader)...",
                                color = ForgeCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    state = viewerListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .horizontalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    itemsIndexed(slice.lines) { idx, line ->
                                        val lineNo = slice.startLineNumber + idx
                                        Row(verticalAlignment = Alignment.Top) {
                                            Text(
                                                text = String.format(Locale.US, "%5d │ ", lineNo),
                                                color = ForgeTextMuted,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp
                                            )
                                            Text(
                                                text = line,
                                                color = when {
                                                    line.startsWith("//") || line.startsWith("#") || line.startsWith(";") -> ForgeEmerald
                                                    line.startsWith("[0x") || line.startsWith("0x") -> ForgeCyan
                                                    else -> ForgeTextPrimary
                                                },
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.5.sp,
                                                lineHeight = 14.sp,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (slice != null && slice.totalPages > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { currentPage = 0 },
                                enabled = slice.currentPage > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.FirstPage, contentDescription = "First", tint = ForgeCyan)
                            }
                            IconButton(
                                onClick = { if (currentPage > 0) currentPage -= 1 },
                                enabled = slice.currentPage > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.NavigateBefore, contentDescription = "Prev", tint = ForgeCyan)
                            }
                            Text(
                                text = "Page ${slice.currentPage + 1} / ${slice.totalPages}",
                                color = ForgeTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            IconButton(
                                onClick = { if (currentPage < slice.totalPages - 1) currentPage += 1 },
                                enabled = slice.currentPage < slice.totalPages - 1,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.NavigateNext, contentDescription = "Next", tint = ForgeCyan)
                            }
                            IconButton(
                                onClick = { currentPage = slice.totalPages - 1 },
                                enabled = slice.currentPage < slice.totalPages - 1,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.LastPage, contentDescription = "Last", tint = ForgeCyan)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = slice != null,
                        onClick = {
                            val pageText = slice?.lines?.joinToString("\n").orEmpty().take(200_000)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText(activePreview.name, pageText))
                        }
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Page", color = ForgeCyan, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                activePreview.file
                            )
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, activePreview.name)
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, activePreview.name))
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = null, tint = ForgeEmerald, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share File", color = ForgeEmerald, fontSize = 11.sp)
                    }
                    TextButton(onClick = { previewFile = null }) {
                        Text("Close", color = ForgeTextSecondary, fontSize = 11.sp)
                    }
                }
            }
        )
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
