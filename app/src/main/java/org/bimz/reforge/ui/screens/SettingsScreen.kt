package org.bimz.reforge.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@Composable
fun SettingsScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val bottomClearance = LocalBottomClearance.current
    var showPatchLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageStats()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WorkstationCard(
                title = "Appearance & Theme",
                subtitle = "High-contrast dark & light workstation palettes"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dark Workstation Theme", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.darkTheme,
                        onCheckedChange = { viewModel.toggleTheme(it) }
                    )
                }
            }
        }

        item {
            WorkstationCard(
                title = "Cache, Auto-Backup & Patch Audit",
                subtitle = "Automatic pre-patch .bak snapshots and dated HEX/r2 patch log"
            ) {
                KeyValueRow("Radare2 & Temp Cache", formatByteSize(state.cacheSizeBytes), monospaceValue = true)
                KeyValueRow("Auto-Backups (RE FORGE/backups)", "${state.backupCount} file(s) • ${formatByteSize(state.backupSizeBytes)}", monospaceValue = true)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.clearAppCaches() },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                    ) {
                        Text("Clear Cache", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearAutoBackups() },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                    ) {
                        Text("Clear Backups", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.refreshStorageStats()
                        showPatchLog = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Text("View Dated Patch Log (HEX & r2)", fontSize = 12.sp)
                }
            }
        }

        item {
            WorkstationCard(
                title = "Radare2 & Native Runtime Scope",
                subtitle = "Local-first ARM64 analysis with zero cloud telemetry"
            ) {
                KeyValueRow("Application ID", "org.bimz.reforge", monospaceValue = true)
                KeyValueRow("Primary Engine", "Radare2 5.9.8 (ARM64)")
                KeyValueRow("Storage Access", "Scoped internal filesDir + Android SAF")
                KeyValueRow("Network Telemetry", "None (0 background network sockets)")
                KeyValueRow("Workspace Disk Usage", formatByteSize(state.workspaceStorageBytes))
            }
        }
    }

    if (showPatchLog) {
        AlertDialog(
            onDismissRequest = { showPatchLog = false },
            title = { Text("Dated Patch History (HEX & Radare2)") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = state.patchHistoryText.ifBlank { "No HEX or Radare2 patches recorded yet." },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        softWrap = false
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPatchLog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
