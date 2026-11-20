package org.bimz.reforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
}
