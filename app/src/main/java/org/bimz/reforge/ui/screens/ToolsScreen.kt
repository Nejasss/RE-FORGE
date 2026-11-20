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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    var commandText by remember { mutableStateOf("help") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WorkstationCard(
                title = "Engine Command Console",
                subtitle = "Restricted to verified RE FORGE engines (help, engines, hash, elf, capstone, r2, clear)"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Engine command") },
                        singleLine = true
                    )
                    Button(
                        onClick = { viewModel.runConsoleCommand(commandText) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Run")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("help", "engines", "hash", "elf", "capstone arm64 0", "r2 ij", "clear").forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                commandText = preset
                                viewModel.runConsoleCommand(preset)
                            },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(preset)
                        }
                    }
                }
            }
        }

        items(state.consoleHistory.asReversed()) { entry ->
            WorkstationCard(
                title = "> ${entry.command}",
                subtitle = if (entry.isError) "Engine Error" else "Engine Output"
            ) {
                Text(
                    text = entry.output,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}
