package org.bimz.reforge.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.ui.WorkstationViewModel
import org.bimz.reforge.ui.screens.EnginesScreen
import org.bimz.reforge.ui.screens.HexEditorScreen
import org.bimz.reforge.ui.screens.HomeScreen
import org.bimz.reforge.ui.screens.SavedFilesScreen
import org.bimz.reforge.ui.screens.SettingsScreen
import org.bimz.reforge.ui.screens.ToolsScreen
import org.bimz.reforge.ui.screens.WorkspaceScreen
import org.bimz.reforge.ui.theme.BorderSubtle
import org.bimz.reforge.ui.theme.CyanAccent
import org.bimz.reforge.ui.theme.EmeraldOk
import org.bimz.reforge.ui.theme.ObsidianBg
import org.bimz.reforge.ui.theme.ObsidianElevated
import org.bimz.reforge.ui.theme.ObsidianSurface
import org.bimz.reforge.ui.theme.TextMuted
import org.bimz.reforge.ui.theme.TextPrimary
import org.bimz.reforge.ui.theme.TextSecondary

enum class WorkstationDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    WORKSPACE("Workspace", Icons.Outlined.Folder),
    SAVED("RE FORGE", Icons.Outlined.FolderSpecial),
    TOOLS("Terminal", Icons.Outlined.Terminal),
    HEX("Hex", Icons.Outlined.Folder),
    ENGINES("Engines", Icons.Outlined.Folder),
    SETTINGS("Settings", Icons.Outlined.Folder)
}

@Composable
fun ReForgeNav(
    viewModel: WorkstationViewModel,
    onOpenFilePicker: () -> Unit,
    onImportToForgePicker: () -> Unit = onOpenFilePicker,
    onSaveDumpRequested: (String) -> Unit = {},
    onSavePatchedRequested: () -> Unit = {}
) {
    var current by rememberSaveable { mutableStateOf(WorkstationDestination.HOME) }
    val state by viewModel.uiState.collectAsState()
    val engines by viewModel.engineRegistry.engines.collectAsState()

    val dockDestinations = listOf(
        WorkstationDestination.HOME,
        WorkstationDestination.WORKSPACE,
        WorkstationDestination.SAVED,
        WorkstationDestination.TOOLS
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimalist Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RE FORGE",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = state.activeFile?.name ?: "No binary loaded",
                        color = if (state.activeFile != null) CyanAccent else TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val enabledCount = engines.count { it.enabled }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.clickable { current = WorkstationDestination.ENGINES }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(EmeraldOk)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$enabledCount/${engines.size} ON",
                            color = EmeraldOk,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Secondary Compact Pill Bar (Hex, Engines, Settings)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    WorkstationDestination.HEX to "Hex",
                    WorkstationDestination.ENGINES to "Engines",
                    WorkstationDestination.SETTINGS to "Settings"
                ).forEach { (dest, title) ->
                    val selected = current == dest
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) CyanAccent.copy(alpha = 0.16f) else ObsidianSurface,
                        border = BorderStroke(1.dp, if (selected) CyanAccent else BorderSubtle),
                        modifier = Modifier.clickable { current = dest }
                    ) {
                        Text(
                            text = title,
                            color = if (selected) CyanAccent else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.statusBanner != null) {
                state.statusBanner?.let { banner ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianElevated,
                        border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = banner,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (banner.contains("RE FORGE/")) {
                                Text(
                                    text = "OPEN",
                                    color = CyanAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            viewModel.dismissBanner()
                                            current = WorkstationDestination.SAVED
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { viewModel.dismissBanner() }
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (current) {
                    WorkstationDestination.HOME -> HomeScreen(
                        state = state,
                        engines = engines,
                        onOpenPicker = onOpenFilePicker,
                        onOpenRecent = {
                            viewModel.openExistingFile(it)
                            current = WorkstationDestination.TOOLS
                        },
                        onNavigate = { current = it }
                    )
                    WorkstationDestination.WORKSPACE -> WorkspaceScreen(
                        state = state,
                        onImportFile = onOpenFilePicker,
                        onSelectFile = {
                            viewModel.openExistingFile(it)
                            current = WorkstationDestination.TOOLS
                        },
                        onDeleteFile = { viewModel.deleteWorkspaceFile(it) }
                    )
                    WorkstationDestination.SAVED -> SavedFilesScreen(
                        state = state,
                        onImportToForge = onImportToForgePicker,
                        onOpenInTerminal = { file ->
                            viewModel.openExistingFile(file)
                            current = WorkstationDestination.TOOLS
                        },
                        onOpenInHex = { file ->
                            viewModel.openExistingFile(file)
                            current = WorkstationDestination.HEX
                        },
                        onDeleteSaved = { item ->
                            viewModel.deleteSavedForgeFile(item)
                        }
                    )
                    WorkstationDestination.HEX -> HexEditorScreen(
                        state = state,
                        onLoadPage = { viewModel.loadHexPage(it) },
                        onPatchByte = { off, value -> viewModel.patchHexByte(off, value) },
                        onRevert = { viewModel.revertHexPatches() },
                        onSavePatched = {
                            viewModel.savePatchedToForgeFolder()
                            onSavePatchedRequested()
                        }
                    )
                    WorkstationDestination.TOOLS -> ToolsScreen(
                        state = state,
                        onRunCommand = { cmd ->
                            viewModel.runConsoleCommand(cmd)
                        },
                        onExitR2Mode = { viewModel.exitR2Mode() },
                        onStopCommand = { viewModel.stopActiveSessionCommand() },
                        onAddSession = { viewModel.addTerminalSession() },
                        onSelectSession = { sid -> viewModel.selectTerminalSession(sid) },
                        onCloseSession = { sid -> viewModel.closeTerminalSession(sid) },
                        onSaveDumpToForge = { explicitText ->
                            val payload = explicitText.ifBlank { viewModel.buildLatestDumpText() }
                            if (payload.isNotBlank()) {
                                viewModel.saveDumpDirectToForge(payload)
                                onSaveDumpRequested(payload)
                            }
                        },
                        onOpenSavedFolder = { current = WorkstationDestination.SAVED }
                    )
                    WorkstationDestination.ENGINES -> EnginesScreen(
                        engines = engines,
                        onToggleEngine = { id, enabled -> viewModel.engineRegistry.setEngineEnabled(id, enabled) }
                    )
                    WorkstationDestination.SETTINGS -> SettingsScreen(
                        workspacePath = viewModel.workspaceManager.workspaceDir.absolutePath,
                        onClearCache = {
                            viewModel.workspaceManager.clearAllCache()
                            viewModel.refreshRecentFiles()
                        }
                    )
                }
            }
        }

        // Minimalist Icon-Only Floating Dock
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            shape = RoundedCornerShape(22.dp),
            color = ObsidianSurface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, BorderSubtle),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dockDestinations.forEach { dest ->
                    val selected = current == dest
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) CyanAccent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { current = dest },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            tint = if (selected) CyanAccent else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
