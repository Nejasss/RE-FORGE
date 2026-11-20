package org.bimz.reforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HexViewerScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val nativeEnabled = state.engines.firstOrNull { it.id == "native_elf_mmap" }?.enabled ?: true
    if (!nativeEnabled) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "Native Mmap Hex Engine Disabled",
                    reason = "Native ELF64/32, Mmap Hex & SHA-256 Engine is OFF. Turn it ON in Engines to restore Hex UI.",
                    actionLabel = "Open Engines",
                    onAction = { viewModel.navigateTo(WorkstationDestination.ENGINES) }
                )
            }
        }
        return
    }

    val project = state.activeProject
    if (project == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "No File Open in Hex Viewer",
                    reason = "Import any file from Home to inspect 256-byte memory-mapped pages.",
                    actionLabel = "Go to Home",
                    onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
                )
            }
        }
        return
    }

    var jumpOffsetHex by remember { mutableStateOf(state.hexPageOffset.toString(16)) }
    var searchHex by remember { mutableStateOf("") }
    var writeUnlocked by remember { mutableStateOf(false) }
    var patchOffsetHex by remember { mutableStateOf("") }
    var patchBytesHex by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WorkstationCard(
                title = "Hex Inspector & Paged Reader",
                subtitle = "Offset 0x${state.hexPageOffset.toString(16)} / ${project.fileSize} bytes"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = jumpOffsetHex,
                        onValueChange = { jumpOffsetHex = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Jump hex offset") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val target = jumpOffsetHex.trim().removePrefix("0x").toLongOrNull(16) ?: 0L
                            viewModel.loadHexPage(target)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Jump")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.loadHexPage((state.hexPageOffset - 256L).coerceAtLeast(0L)) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Prev 256B")
                    }
                    OutlinedButton(
                        onClick = { viewModel.loadHexPage(state.hexPageOffset + 256L) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Next 256B")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchHex,
                        onValueChange = { searchHex = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Search hex bytes (e.g. 7F 45 4C 46)") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = { viewModel.searchHexBytes(searchHex) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Find")
                    }
                }
                if (state.hexSearchStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = state.hexSearchStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            WorkstationCard(
                title = "Hex + ASCII Dump (8 bytes/row)"
            ) {
                val dumpText = formatHexRows(state.hexPageOffset, state.hexPageBytes)
                Text(
                    text = dumpText.ifBlank { "End of file reached." },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        item {
            WorkstationCard(
                title = "Authorized Byte Patcher",
                subtitle = "Read-only by default; patches apply strictly to workspace working copy"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Unlock Working Copy Write Mode", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = writeUnlocked, onCheckedChange = { writeUnlocked = it })
                }

                if (writeUnlocked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = patchOffsetHex,
                        onValueChange = { patchOffsetHex = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Target hex offset (e.g. 1A0)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = patchBytesHex,
                        onValueChange = { patchBytesHex = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Replacement hex bytes (e.g. 1F 20 03 D5)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.patchHexBytes(patchOffsetHex, patchBytesHex) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Apply Byte Patch")
                        }
                        OutlinedButton(
                            onClick = { viewModel.undoHexPatch() },
                            enabled = state.hexCanUndo,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Undo")
                        }
                        OutlinedButton(
                            onClick = { viewModel.redoHexPatch() },
                            enabled = state.hexCanRedo,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Redo")
                        }
                    }
                }
            }
        }
    }
}

private fun formatHexRows(baseOffset: Long, bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val rowOff = (baseOffset + i).toString(16).padStart(8, '0')
        sb.append(rowOff).append("  ")
        val ascii = StringBuilder()
        for (col in 0 until 8) {
            if (i + col < bytes.size) {
                val b = bytes[i + col].toInt() and 0xFF
                sb.append("%02X ".format(b))
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            } else {
                sb.append("   ")
                ascii.append(' ')
            }
        }
        sb.append(" ").append(ascii).append('\n')
        i += 8
    }
    return sb.toString()
}
