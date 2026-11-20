package org.bimz.reforge.ui.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bimz.reforge.ui.components.ErrorActionState
import org.bimz.reforge.ui.screens.ApkInspectorScreen
import org.bimz.reforge.ui.screens.BinaryAnalysisScreen
import org.bimz.reforge.ui.screens.DexViewerScreen
import org.bimz.reforge.ui.screens.EnginesScreen
import org.bimz.reforge.ui.screens.HexViewerScreen
import org.bimz.reforge.ui.screens.HomeScreen
import org.bimz.reforge.ui.screens.SettingsScreen
import org.bimz.reforge.ui.screens.ToolsScreen
import org.bimz.reforge.ui.screens.WorkspaceScreen
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReForgeAppShell(viewModel: WorkstationViewModel) {
    val state by viewModel.uiState.collectAsState()

    val primaryBottomItems = listOf(
        WorkstationDestination.HOME to Icons.Outlined.Home,
        WorkstationDestination.WORKSPACE to Icons.Outlined.FolderOpen,
        WorkstationDestination.APK to Icons.Outlined.Android,
        WorkstationDestination.DEX to Icons.Outlined.Code,
        WorkstationDestination.BINARY to Icons.Outlined.Memory
    )

    val secondaryHeaderItems = listOf(
        WorkstationDestination.HEX,
        WorkstationDestination.TOOLS,
        WorkstationDestination.ENGINES,
        WorkstationDestination.SETTINGS
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "RE FORGE",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = state.activeProject?.originalFileName ?: "ARM64 Workstation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    secondaryHeaderItems.forEach { dest ->
                        FilterChip(
                            selected = state.currentRoute == dest,
                            onClick = { viewModel.navigateTo(dest) },
                            label = { Text(dest.label) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        )
                    }
                }
                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.busyMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                primaryBottomItems.forEach { (dest, icon) ->
                    NavigationBarItem(
                        selected = state.currentRoute == dest,
                        onClick = { viewModel.navigateTo(dest) },
                        icon = { Icon(imageVector = icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.errorMessage != null) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        ErrorActionState(
                            title = "Operation Error",
                            cause = state.errorMessage.orEmpty(),
                            retryLabel = "Dismiss",
                            onRetry = { viewModel.clearError() }
                        )
                    }
                }
                when (state.currentRoute) {
                    WorkstationDestination.HOME -> HomeScreen(state, viewModel)
                    WorkstationDestination.WORKSPACE -> WorkspaceScreen(state, viewModel)
                    WorkstationDestination.APK -> ApkInspectorScreen(state, viewModel)
                    WorkstationDestination.DEX -> DexViewerScreen(state, viewModel)
                    WorkstationDestination.BINARY -> BinaryAnalysisScreen(state, viewModel)
                    WorkstationDestination.HEX -> HexViewerScreen(state, viewModel)
                    WorkstationDestination.TOOLS -> ToolsScreen(state, viewModel)
                    WorkstationDestination.ENGINES -> EnginesScreen(state, viewModel)
                    WorkstationDestination.SETTINGS -> SettingsScreen(state, viewModel)
                }
            }
        }
    }
}
