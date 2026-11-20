package org.bimz.reforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@Composable
fun SettingsScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WorkstationCard(
                title = "Appearance & Theme",
                subtitle = "Both dark and light palettes are verified for WCAG AA contrast"
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
                title = "Security & Scope Boundaries",
                subtitle = "Local-first architecture with zero cloud telemetry"
            ) {
                KeyValueRow("Application ID", "org.bimz.reforge", monospaceValue = true)
                KeyValueRow("Storage Access", "Scoped internal filesDir + Android SAF")
                KeyValueRow("Network Telemetry", "None (0 background network sockets)")
                KeyValueRow("Signing Keys", "Stored locally in AndroidKeyStore / user PKCS#12")
                KeyValueRow("Workspace Disk Usage", formatByteSize(state.workspaceStorageBytes))
                KeyValueRow(
                    "Intended Use",
                    "Authorized reverse engineering, security auditing, debugging, and educational binary analysis."
                )
            }
        }
    }
}
