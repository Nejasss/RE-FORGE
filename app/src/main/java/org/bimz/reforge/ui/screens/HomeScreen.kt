package org.bimz.reforge.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bimz.reforge.engines.EngineStatus
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    var pendingTargetRoute by remember { mutableStateOf<WorkstationDestination?>(null) }
    val bottomClearance = LocalBottomClearance.current

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importFileFromUri(uri, pendingTargetRoute)
        }
    }

    val r2Engine = state.engines.firstOrNull { it.id == "radare2_core" }
    val r2Ready = r2Engine?.enabled == true && r2Engine.initialized
    val activeCount = state.engines.count {
        it.enabled && (it.status == EngineStatus.INTEGRATED || it.status == EngineStatus.PARTIALLY_INTEGRATED)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            WorkstationCard(
                title = "Radare2 ARM64 Workstation",
                subtitle = "Native reverse-engineering & binary analysis environment"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        label = if (r2Ready) "Radare2 Ready" else "Radare2 Offline",
                        tone = if (r2Ready) BadgeTone.PASS else BadgeTone.WARNING
                    )
                    StatusPill(
                        label = "$activeCount/${state.engines.size} Engines",
                        tone = if (activeCount > 0) BadgeTone.PASS else BadgeTone.FAILED
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                KeyValueRow("App Version", state.appVersion, monospaceValue = true)
                KeyValueRow("Android OS", state.androidVersion)
                KeyValueRow("Supported ABIs", state.deviceAbi, monospaceValue = true)
                KeyValueRow("Storage Used", formatByteSize(state.workspaceStorageBytes))
                KeyValueRow(
                    "Active Target",
                    state.activeProject?.originalFileName ?: "None loaded"
                )
            }
        }

        item {
            WorkstationCard(
                title = "Open Target in Radare2",
                subtitle = "Imports file via SAF into an isolated read-safe workspace copy"
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            pendingTargetRoute = WorkstationDestination.TOOLS
                            openDocLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open Binary / ELF / SO")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingTargetRoute = WorkstationDestination.TOOLS
                            openDocLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open in Terminal")
                    }
                    OutlinedButton(
                        onClick = {
                            if (state.activeFile != null) {
                                viewModel.navigateTo(WorkstationDestination.HEX)
                            } else {
                                pendingTargetRoute = WorkstationDestination.HEX
                                openDocLauncher.launch(arrayOf("*/*"))
                            }
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open Hex")
                    }
                }
            }
        }

        item {
            Text(
                text = "Recent Workspaces (${state.projects.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (state.projects.isEmpty()) {
            item {
                EmptyActionState(
                    title = "No Imported Targets",
                    reason = "Select any executable, .so library, firmware dump, or script to analyze with Radare2.",
                    actionLabel = "Select File from Device",
                    onAction = {
                        pendingTargetRoute = WorkstationDestination.TOOLS
                        openDocLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
        } else {
            items(state.projects, key = { it.id }) { project ->
                WorkstationCard(
                    title = project.originalFileName,
                    subtitle = "${project.detectedFormat.label} | ${formatByteSize(project.fileSize)}"
                ) {
                    KeyValueRow("SHA-256", project.sha256.take(24) + "...", monospaceValue = true)
                    KeyValueRow("Magic", project.magicHex.ifBlank { "n/a" }, monospaceValue = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.selectProject(project, WorkstationDestination.TOOLS) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Terminal")
                        }
                        OutlinedButton(
                            onClick = { viewModel.selectProject(project, WorkstationDestination.WORKSPACE) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("File Info")
                        }
                        OutlinedButton(
                            onClick = { viewModel.deleteProject(project.id) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
