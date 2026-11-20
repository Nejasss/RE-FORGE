package org.bimz.reforge.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.bimz.reforge.engines.EngineStatus
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.ErrorActionState
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.screens.SavedFilesScreen
import org.bimz.reforge.ui.screens.EnginesScreen
import org.bimz.reforge.ui.screens.HexViewerScreen
import org.bimz.reforge.ui.screens.HomeScreen
import org.bimz.reforge.ui.screens.SettingsScreen
import org.bimz.reforge.ui.screens.ToolsScreen
import org.bimz.reforge.ui.screens.WorkspaceScreen
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationViewModel

val LocalBottomClearance = compositionLocalOf { 96.dp }
val LocalIsKeyboardOpen = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReForgeAppShell(viewModel: WorkstationViewModel) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current

    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val isKeyboardOpen = imeBottomDp > navBottomDp + 24.dp
    val bottomClearance: Dp = if (isKeyboardOpen) imeBottomDp + 6.dp else navBottomDp + 88.dp

    val primaryDockItems = listOf(
        WorkstationDestination.HOME to Icons.Outlined.Home,
        WorkstationDestination.WORKSPACE to Icons.Outlined.FolderOpen,
        WorkstationDestination.BINARY to Icons.Outlined.FolderSpecial,
        WorkstationDestination.TOOLS to Icons.Outlined.Terminal
    )

    val secondaryHeaderItems = listOf(
        WorkstationDestination.HEX,
        WorkstationDestination.ENGINES,
        WorkstationDestination.SETTINGS
    )

    val activeEngineCount = state.engines.count {
        it.enabled && (it.status == EngineStatus.INTEGRATED || it.status == EngineStatus.PARTIALLY_INTEGRATED)
    }

    CompositionLocalProvider(
        LocalBottomClearance provides bottomClearance,
        LocalIsKeyboardOpen provides isKeyboardOpen
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars,
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
                                    text = state.activeProject?.originalFileName ?: "Radare2 ARM64 Workstation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            Box(modifier = Modifier.padding(end = 12.dp)) {
                                StatusPill(
                                    label = "$activeEngineCount/${state.engines.size} ON",
                                    tone = if (activeEngineCount > 0) BadgeTone.PASS else BadgeTone.NEUTRAL
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
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = state.isBusy,
                        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(160)) + fadeOut(tween(140))
                    ) {
                        Column {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.busyMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                )
                            }
                        }
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
                    AnimatedVisibility(
                        visible = state.errorMessage != null,
                        enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(160)) + fadeOut(tween(140))
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            ErrorActionState(
                                title = "Operation Error",
                                cause = state.errorMessage.orEmpty(),
                                retryLabel = "Dismiss",
                                onRetry = { viewModel.clearError() }
                            )
                        }
                    }

                    AnimatedContent(
                        targetState = state.currentRoute,
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            val offsetSign = if (forward) 1 else -1
                            (fadeIn(tween(180)) + slideInHorizontally(tween(200)) { offsetSign * (it / 16) })
                                .togetherWith(
                                    fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { -offsetSign * (it / 16) }
                                )
                        },
                        label = "route_transition",
                        modifier = Modifier.fillMaxSize()
                    ) { route ->
                        when (route) {
                            WorkstationDestination.HOME -> HomeScreen(state, viewModel)
                            WorkstationDestination.WORKSPACE -> WorkspaceScreen(state, viewModel)
                            WorkstationDestination.BINARY -> SavedFilesScreen(state = state, viewModel = viewModel)
                            WorkstationDestination.TOOLS -> ToolsScreen(state, viewModel)
                            WorkstationDestination.HEX -> HexViewerScreen(state, viewModel)
                            WorkstationDestination.ENGINES -> EnginesScreen(state, viewModel)
                            WorkstationDestination.SETTINGS -> SettingsScreen(state, viewModel)
                        }
                    }
                }

                // Floating Pill Navigation Dock (auto-hides when keyboard is open)
                AnimatedVisibility(
                    visible = !isKeyboardOpen,
                    enter = slideInVertically(tween(200)) { it } + fadeIn(tween(180)),
                    exit = slideOutVertically(tween(150)) { it } + fadeOut(tween(120)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBottomDp + 12.dp)
                ) {
                    FloatingWorkstationDock(
                        items = primaryDockItems,
                        currentRoute = state.currentRoute,
                        onSelect = { viewModel.navigateTo(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingWorkstationDock(
    items: List<Pair<WorkstationDestination, ImageVector>>,
    currentRoute: WorkstationDestination,
    onSelect: (WorkstationDestination) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF151C26),
        border = BorderStroke(1.dp, Color(0xFF283446)),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (dest, icon) ->
                val selected = currentRoute == dest
                val slotBg by animateColorAsState(
                    targetValue = if (selected) Color(0xFF2E3E52) else Color.Transparent,
                    animationSpec = tween(180),
                    label = "dock_slot_bg"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (selected) Color(0xFF7DD3FC) else Color(0xFF94A3B8),
                    animationSpec = tween(180),
                    label = "dock_icon_tint"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(slotBg)
                        .clickable { onSelect(dest) }
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = dest.label,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
