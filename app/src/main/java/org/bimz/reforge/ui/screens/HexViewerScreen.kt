package org.bimz.reforge.ui.screens

import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private class PagedFileReader(
    private val file: File,
    @Suppress("unused") private val revision: Int
) {
    private val blockSize = 4096
    private val cache = LruCache<Long, ByteArray>(32) // 128 KB RAM cache

    fun readBytes(offset: Long, length: Int): ByteArray {
        if (!file.exists() || offset < 0L || offset >= file.length() || length <= 0) {
            return ByteArray(0)
        }
        val maxAvailable = minOf(length.toLong(), file.length() - offset).toInt()
        val result = ByteArray(maxAvailable)
        var copied = 0
        while (copied < maxAvailable) {
            val curOffset = offset + copied
            val blockIndex = curOffset / blockSize
            val inBlockOffset = (curOffset % blockSize).toInt()
            val block = getBlock(blockIndex)
            if (inBlockOffset >= block.size) break
            val take = minOf(maxAvailable - copied, block.size - inBlockOffset)
            System.arraycopy(block, inBlockOffset, result, copied, take)
            copied += take
        }
        return if (copied == maxAvailable) result else result.copyOf(copied)
    }

    private fun getBlock(blockIndex: Long): ByteArray {
        cache.get(blockIndex)?.let { return it }
        val start = blockIndex * blockSize
        val data = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val toRead = minOf(blockSize.toLong(), (raf.length() - start).coerceAtLeast(0L)).toInt()
                val buf = ByteArray(toRead)
                var total = 0
                while (total < toRead) {
                    val r = raf.read(buf, total, toRead - total)
                    if (r <= 0) break
                    total += r
                }
                if (total == toRead) buf else buf.copyOf(total)
            }
        }.getOrDefault(ByteArray(0))
        cache.put(blockIndex, data)
        return data
    }
}

@Composable
fun HexViewerScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val bottomClearance = LocalBottomClearance.current
    val scope = rememberCoroutineScope()

    val nativeEnabled = state.engines.firstOrNull { it.id == "native_elf_mmap" }?.enabled ?: true
    if (!nativeEnabled) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            EmptyActionState(
                title = "Native Mmap Hex Engine Disabled",
                reason = "Native ELF64/32, Mmap Hex & SHA-256 Engine is OFF. Turn it ON in Engines to restore Hex Editor.",
                actionLabel = "Open Engines",
                onAction = { viewModel.navigateTo(WorkstationDestination.ENGINES) }
            )
        }
        return
    }

    val project = state.activeProject
    val resolvedFile = state.activeFile
    if (resolvedFile == null || !resolvedFile.exists()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            EmptyActionState(
                title = "No File Open in Hex Editor",
                reason = "Import any binary, .so library, or file from Home to inspect and edit bytes.",
                actionLabel = "Go to Home",
                onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
            )
        }
        return
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportWorkingCopyToUri(uri)
        }
    }

    val workingFile = remember(resolvedFile.absolutePath) { resolvedFile }
    val fileSize = workingFile.length().coerceAtLeast(project?.fileSize ?: 0L)
    val bytesPerRow = 8
    val totalRows = ((fileSize + bytesPerRow - 1L) / bytesPerRow).toInt().coerceAtLeast(1)

    val reader = remember(resolvedFile.absolutePath, state.hexRevision) {
        PagedFileReader(workingFile, state.hexRevision)
    }

    val listState = rememberLazyListState()
    var selectedOffset by remember(resolvedFile.absolutePath) { mutableLongStateOf(0L) }
    var showGotoDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showEditorDrawer by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchAsciiMode by remember { mutableStateOf(false) }

    var editHexInput by remember { mutableStateOf("") }
    var editAsciiInput by remember { mutableStateOf("") }
    var editAsciiMode by remember { mutableStateOf(false) }
    var fillCountInput by remember { mutableStateOf("4") }
    var fillByteInput by remember { mutableStateOf("00") }

    val selectedBytes8 = remember(selectedOffset, state.hexRevision) {
        reader.readBytes(selectedOffset, 8)
    }

    LaunchedEffect(selectedOffset, selectedBytes8) {
        val firstByte = selectedBytes8.firstOrNull()
        if (firstByte != null) {
            editHexInput = "%02X".format(firstByte.toInt() and 0xFF)
            val ch = firstByte.toInt() and 0xFF
            editAsciiInput = if (ch in 0x20..0x7E) ch.toChar().toString() else "."
        }
    }

    val topVisibleOffset by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex.toLong() * bytesPerRow).coerceAtMost((fileSize - 1L).coerceAtLeast(0L))
        }
    }

    val editorBg = Color(0xFF0B0F17)
    val panelBg = Color(0xFF131A26)
    val borderCol = Color(0xFF263244)
    val accentBlue = Color(0xFF38BDF8)
    val accentGreen = Color(0xFF4ADE80)
    val accentAmber = Color(0xFFFBBF24)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 10.dp,
                end = 10.dp,
                top = 6.dp,
                bottom = bottomClearance
            )
    ) {
        // 1. Play Store Hex Editor Top Toolbar
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = panelBg,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Offset Indicator -> opens Go-To Offset Dialog
                Surface(
                    onClick = { showGotoDialog = true },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1C2636),
                    border = BorderStroke(1.dp, borderCol),
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "0x${selectedOffset.toString(16).uppercase().padStart(8, '0')}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = accentBlue
                        )
                        Text(
                            text = "Size: $fileSize B (Tap Goto)",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "Search Hex or ASCII",
                            tint = if (showSearchBar) accentBlue else Color(0xFFCBD5E1)
                        )
                    }
                    IconButton(
                        onClick = { showEditorDrawer = !showEditorDrawer },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit Bytes",
                            tint = if (showEditorDrawer) accentGreen else Color(0xFFCBD5E1)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.undoHexPatch() },
                        enabled = state.hexCanUndo,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Undo,
                            contentDescription = "Undo",
                            tint = if (state.hexCanUndo) Color.White else Color(0xFF475569)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redoHexPatch() },
                        enabled = state.hexCanRedo,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Redo,
                            contentDescription = "Redo",
                            tint = if (state.hexCanRedo) Color.White else Color(0xFF475569)
                        )
                    }
                    IconButton(
                        onClick = { exportLauncher.launch(project?.originalFileName ?: resolvedFile.name) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Save,
                            contentDescription = "Save Patched File",
                            tint = accentAmber
                        )
                    }
                }
            }
        }

        // 2. Collapsible Search Bar (Hex & ASCII Find / Find Next)
        if (showSearchBar) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = panelBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !searchAsciiMode,
                            onClick = { searchAsciiMode = false },
                            label = { Text("HEX") }
                        )
                        FilterChip(
                            selected = searchAsciiMode,
                            onClick = { searchAsciiMode = true },
                            label = { Text("ASCII") }
                        )
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(if (searchAsciiMode) "e.g. main" else "e.g. 7F 45 4C 46")
                            },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val nextStart = if (state.hexLastMatchOffset == selectedOffset) selectedOffset + 1L else selectedOffset
                                viewModel.searchHexOrAscii(searchQuery, searchAsciiMode, nextStart) { foundOffset, _ ->
                                    selectedOffset = foundOffset
                                    scope.launch {
                                        listState.scrollToItem((foundOffset / bytesPerRow).toInt())
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Find")
                        }
                    }
                    if (state.hexSearchStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.hexSearchStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = accentBlue
                            )
                            TextButton(
                                onClick = {
                                    viewModel.searchHexOrAscii(searchQuery, searchAsciiMode, 0L) { foundOffset, _ ->
                                        selectedOffset = foundOffset
                                        scope.launch {
                                            listState.scrollToItem((foundOffset / bytesPerRow).toInt())
                                        }
                                    }
                                }
                            ) {
                                Text("From 0x0", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3. Live Multi-Type Data Inspector Strip (INT8/16/32, UINT, FLOAT32, BIN)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF101722),
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val b0 = selectedBytes8.getOrNull(0)?.toInt()?.and(0xFF)
                val i8 = selectedBytes8.getOrNull(0)?.toInt()
                val u16 = if (selectedBytes8.size >= 2) {
                    ByteBuffer.wrap(selectedBytes8, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                } else null
                val i16 = if (selectedBytes8.size >= 2) {
                    ByteBuffer.wrap(selectedBytes8, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                } else null
                val u32 = if (selectedBytes8.size >= 4) {
                    ByteBuffer.wrap(selectedBytes8, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                } else null
                val i32 = if (selectedBytes8.size >= 4) {
                    ByteBuffer.wrap(selectedBytes8, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                } else null
                val f32 = if (selectedBytes8.size >= 4) {
                    ByteBuffer.wrap(selectedBytes8, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float
                } else null

                InspectorChip("U8", b0?.toString() ?: "-")
                InspectorChip("I8", i8?.toString() ?: "-")
                InspectorChip("U16", u16?.toString() ?: "-")
                InspectorChip("I16", i16?.toString() ?: "-")
                InspectorChip("U32", u32?.toString() ?: "-")
                InspectorChip("I32", i32?.toString() ?: "-")
                InspectorChip("F32", f32?.let { String.format(Locale.US, "%.3g", it) } ?: "-")
                InspectorChip("BIN", b0?.toString(2)?.padStart(8, '0') ?: "-")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4. Main Scrollable Hex + ASCII Matrix with Column Headers & Fast Scrubber
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = editorBg,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Sticky Column Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF151E2C))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OFFSET  ",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = accentBlue,
                        modifier = Modifier.width(66.dp)
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (c in 0 until bytesPerRow) {
                            Text(
                                text = "%02X".format(c),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ASCII   ",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = accentGreen,
                        modifier = Modifier.width(68.dp)
                    )
                }

                // Virtualized Scrollable Rows + Right Fast-Scroll Thumb
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 6.dp, end = 18.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(count = totalRows, key = { it }) { rowIndex ->
                            val rowOffset = rowIndex.toLong() * bytesPerRow
                            val rowBytes = reader.readBytes(rowOffset, bytesPerRow)
                            val isRowSelected = selectedOffset in rowOffset until (rowOffset + bytesPerRow)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isRowSelected) Color(0xFF162235) else Color.Transparent)
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Offset Column
                                Text(
                                    text = rowOffset.toString(16).uppercase().padStart(8, '0'),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = if (isRowSelected) accentBlue else Color(0xFF64748B),
                                    modifier = Modifier.width(66.dp)
                                )

                                // 8 Interactive Hex Cells
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    for (col in 0 until bytesPerRow) {
                                        val byteAddr = rowOffset + col
                                        if (col < rowBytes.size) {
                                            val bVal = rowBytes[col].toInt() and 0xFF
                                            val isSelected = byteAddr == selectedOffset
                                            val isSearchMatch = state.hexLastMatchOffset >= 0L &&
                                                byteAddr in state.hexLastMatchOffset until (state.hexLastMatchOffset + state.hexLastMatchLength)

                                            val cellBg = when {
                                                isSelected -> accentBlue
                                                isSearchMatch -> Color(0xFF854D0E)
                                                else -> Color.Transparent
                                            }
                                            val cellFg = when {
                                                isSelected -> Color(0xFF06101D)
                                                isSearchMatch -> Color(0xFFFEF08A)
                                                bVal == 0 -> Color(0xFF475569)
                                                else -> Color(0xFFE2E8F0)
                                            }

                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(cellBg)
                                                    .clickable {
                                                        selectedOffset = byteAddr
                                                        showEditorDrawer = true
                                                    }
                                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "%02X".format(bVal),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 11.sp
                                                    ),
                                                    color = cellFg
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "  ",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // 8 Interactive ASCII Cells
                                Row(modifier = Modifier.width(68.dp)) {
                                    for (col in 0 until bytesPerRow) {
                                        val byteAddr = rowOffset + col
                                        if (col < rowBytes.size) {
                                            val bVal = rowBytes[col].toInt() and 0xFF
                                            val ch = if (bVal in 0x20..0x7E) bVal.toChar() else '.'
                                            val isSelected = byteAddr == selectedOffset
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(if (isSelected) accentGreen else Color.Transparent)
                                                    .clickable {
                                                        selectedOffset = byteAddr
                                                        showEditorDrawer = true
                                                    }
                                            ) {
                                                Text(
                                                    text = ch.toString(),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 11.sp
                                                    ),
                                                    color = when {
                                                        isSelected -> Color(0xFF052E16)
                                                        bVal in 0x20..0x7E -> Color(0xFFCBD5E1)
                                                        else -> Color(0xFF475569)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fast Vertical Scrubber Track on Right Edge
                    FastScrollBar(
                        totalItems = totalRows,
                        firstVisibleItem = listState.firstVisibleItemIndex,
                        currentOffset = topVisibleOffset,
                        onScrubToItem = { targetRow ->
                            scope.launch {
                                listState.scrollToItem(targetRow.coerceIn(0, totalRows - 1))
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(16.dp)
                    )
                }
            }
        }

        // 5. Inline Byte & ASCII Patcher Drawer (when a byte is tapped or Edit icon clicked)
        if (showEditorDrawer) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = panelBg,
                border = BorderStroke(1.dp, accentBlue.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Edit @ 0x${selectedOffset.toString(16).uppercase()}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = accentBlue
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = !editAsciiMode,
                                onClick = { editAsciiMode = false },
                                label = { Text("HEX") }
                            )
                            FilterChip(
                                selected = editAsciiMode,
                                onClick = { editAsciiMode = true },
                                label = { Text("ASCII") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = if (editAsciiMode) editAsciiInput else editHexInput,
                            onValueChange = {
                                if (editAsciiMode) editAsciiInput = it else editHexInput = it
                            },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(if (editAsciiMode) "ASCII text to write" else "Hex bytes (e.g. 1F 20 03 D5)")
                            },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val hexPayload = if (editAsciiMode) {
                                    editAsciiInput.toByteArray(Charsets.UTF_8)
                                        .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                                } else {
                                    editHexInput
                                }
                                viewModel.patchHexBytes(selectedOffset.toString(16), hexPayload)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Write")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Fill Range Helper (e.g. NOP-fill or zero-fill N bytes)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = fillCountInput,
                            onValueChange = { fillCountInput = it },
                            modifier = Modifier.width(84.dp),
                            label = { Text("Count") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = fillByteInput,
                            onValueChange = { fillByteInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Fill byte (e.g. 00)") },
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                val count = fillCountInput.trim().toIntOrNull()?.coerceIn(1, 4096) ?: 1
                                val cleanByte = fillByteInput.trim().removePrefix("0x").take(2).padStart(2, '0')
                                val repeated = List(count) { cleanByte }.joinToString(" ")
                                viewModel.patchHexBytes(selectedOffset.toString(16), repeated)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Fill")
                        }
                    }
                }
            }
        }
    }

    // Go-To Offset Dialog (supports Hex & Decimal)
    if (showGotoDialog) {
        var gotoInput by remember { mutableStateOf(selectedOffset.toString(16).uppercase()) }
        var isDecimal by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showGotoDialog = false },
            title = { Text("Go To Offset") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isDecimal,
                            onClick = { isDecimal = false },
                            label = { Text("Hexadecimal") }
                        )
                        FilterChip(
                            selected = isDecimal,
                            onClick = { isDecimal = true },
                            label = { Text("Decimal") }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gotoInput,
                        onValueChange = { gotoInput = it },
                        label = { Text(if (isDecimal) "Decimal offset (0..${fileSize - 1})" else "Hex offset (e.g. 1A40)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = if (isDecimal) {
                            gotoInput.trim().toLongOrNull()
                        } else {
                            gotoInput.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                        }
                        if (parsed != null && fileSize > 0L) {
                            val clamped = parsed.coerceIn(0L, fileSize - 1L)
                            selectedOffset = clamped
                            showGotoDialog = false
                            scope.launch {
                                listState.scrollToItem((clamped / bytesPerRow).toInt())
                            }
                        }
                    }
                ) {
                    Text("Jump")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGotoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InspectorChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = Color(0xFF64748B)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            color = Color(0xFFE2E8F0)
        )
    }
}

@Composable
private fun FastScrollBar(
    totalItems: Int,
    firstVisibleItem: Int,
    @Suppress("unused") currentOffset: Long,
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
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1E293B))
        )
        val thumbOffsetRatio = fraction
        Column(modifier = Modifier.fillMaxHeight()) {
            if (thumbOffsetRatio > 0.001f) {
                Spacer(modifier = Modifier.weight(thumbOffsetRatio))
            }
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF38BDF8))
            )
            if (1f - thumbOffsetRatio > 0.001f) {
                Spacer(modifier = Modifier.weight(1f - thumbOffsetRatio))
            }
        }
    }
}
