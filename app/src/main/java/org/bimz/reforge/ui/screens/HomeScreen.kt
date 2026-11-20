package org.bimz.reforge.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.engines.EngineStatus
import org.bimz.reforge.terminal.ForgeDumpGenerator
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
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

@Composable
fun HomeScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val context = LocalContext.current
    var pendingTargetRoute by rememberSaveable { mutableStateOf<WorkstationDestination?>(null) }
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
            .background(ForgeBg)
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomClearance + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ForgeSurface,
                border = BorderStroke(1.dp, ForgeBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(
                                label = if (r2Ready) "r2 Ready" else "r2 Offline",
                                tone = if (r2Ready) BadgeTone.PASS else BadgeTone.WARNING
                            )
                            StatusPill(
                                label = "$activeCount/${state.engines.size} Engines",
                                tone = if (activeCount > 0) BadgeTone.PASS else BadgeTone.FAILED
                            )
                        }
                        Text(
                            text = "v${state.appVersion} • ${formatByteSize(state.workspaceStorageBytes)}",
                            color = ForgeTextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                pendingTargetRoute = WorkstationDestination.TOOLS
                                openDocLauncher.launch(arrayOf("*/*"))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ForgeCyan,
                                contentColor = ForgeBg
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp)
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Binary", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.navigateTo(WorkstationDestination.TOOLS) },
                            border = BorderStroke(1.dp, ForgeBorder),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp)
                        ) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Terminal", color = ForgeTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Workspaces (${state.projects.size})",
                    color = ForgeTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = state.deviceAbi.substringBefore(","),
                    color = ForgeTextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        if (state.projects.isEmpty()) {
            item {
                EmptyActionState(
                    title = "No Imported Targets",
                    reason = "Import an ELF executable, .so library, or binary to start analyzing in Radare2.",
                    actionLabel = "Select File",
                    onAction = {
                        pendingTargetRoute = WorkstationDestination.TOOLS
                        openDocLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
        } else {
            items(state.projects, key = { it.id }) { project ->
                val isSelected = state.activeProject?.id == project.id
                val hasAaaCache = remember(project.sha256) {
                    ForgeDumpGenerator.isAaaCached(context, project.sha256)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ForgeSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) ForgeCyan.copy(alpha = 0.6f) else ForgeBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = project.originalFileName,
                                        color = ForgeTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (hasAaaCache) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = ForgeEmerald.copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, ForgeEmerald.copy(alpha = 0.45f))
                                        ) {
                                            Text(
                                                text = "aaa cached",
                                                color = ForgeEmerald,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${project.detectedFormat.label} • ${formatByteSize(project.fileSize)} • ${project.sha256.take(12)}",
                                    color = ForgeTextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.selectProject(project, WorkstationDestination.TOOLS) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ForgeCyan,
                                    contentColor = ForgeBg
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 40.dp)
                            ) {
                                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Terminal", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.selectProject(project, WorkstationDestination.WORKSPACE) },
                                border = BorderStroke(1.dp, ForgeBorder),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 40.dp)
                            ) {
                                Icon(Icons.Outlined.Info, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Inspect", color = ForgeTextPrimary, fontSize = 12.sp)
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ForgeElevated,
                                border = BorderStroke(1.dp, ForgeBorder),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable { viewModel.deleteProject(project.id) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = ForgeRose,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
