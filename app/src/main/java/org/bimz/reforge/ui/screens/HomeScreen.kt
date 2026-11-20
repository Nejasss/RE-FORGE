package org.bimz.reforge.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importFileFromUri(uri, pendingTargetRoute)
        }
    }

    val integratedCount = state.engines.count {
        it.status == EngineStatus.INTEGRATED || it.status == EngineStatus.PARTIALLY_INTEGRATED
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            WorkstationCard(
                title = "System & Runtime Status",
                subtitle = "Native Android ARM64 Reverse-Engineering Workstation"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        label = "$integratedCount/${state.engines.size} Engines Active",
                        tone = if (integratedCount > 0) BadgeTone.PASS else BadgeTone.FAILED
                    )
                    StatusPill(
                        label = "Storage: ${formatByteSize(state.workspaceStorageBytes)}",
                        tone = BadgeTone.NEUTRAL
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                KeyValueRow("App Version", state.appVersion, monospaceValue = true)
                KeyValueRow("Android OS", state.androidVersion)
                KeyValueRow("Supported ABIs", state.deviceAbi, monospaceValue = true)
                KeyValueRow(
                    "Active Target",
                    state.activeProject?.originalFileName ?: "None loaded"
                )
            }
        }

        item {
            WorkstationCard(
                title = "Import Target File",
                subtitle = "Opens via Android Storage Access Framework into an isolated workspace copy"
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            pendingTargetRoute = WorkstationDestination.APK
                            openDocLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open APK")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingTargetRoute = WorkstationDestination.DEX
                            openDocLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open DEX")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingTargetRoute = WorkstationDestination.BINARY
                            openDocLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open ELF / SO")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingTargetRoute = WorkstationDestination.HEX
                            openDocLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open Binary")
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(WorkstationDestination.TOOLS) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Tools & Console")
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
                    title = "No Imported Workspaces",
                    reason = "Import an APK, DEX, ELF, or raw binary file to create an isolated project directory.",
                    actionLabel = "Select File from Device",
                    onAction = {
                        pendingTargetRoute = null
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.selectProject(project) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Open Workspace")
                        }
                        OutlinedButton(
                            onClick = { viewModel.deleteProject(project.id) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        if (state.recentAnalysis.isNotEmpty()) {
            item {
                WorkstationCard(
                    title = "Recent Analysis Log",
                    subtitle = "Recorded local engine operations"
                ) {
                    state.recentAnalysis.take(8).forEach { entry ->
                        Text(
                            text = "[${entry.engineName}] ${entry.projectName}: ${entry.summary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
