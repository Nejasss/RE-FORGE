package org.bimz.reforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val bottomClearance = LocalBottomClearance.current
    val project = state.activeProject
    if (project == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = bottomClearance)
        ) {
            item {
                EmptyActionState(
                    title = "No Active Target Selected",
                    reason = "Open or import a file from Home to inspect hashes, compare binaries, and record analyst notes.",
                    actionLabel = "Go to Home",
                    onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
                )
            }
        }
        return
    }

    var notesDraft by remember(project.id) { mutableStateOf(project.notes) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            WorkstationCard(
                title = project.originalFileName,
                subtitle = "Original file preserved read-only; edits apply to isolated working copy"
            ) {
                KeyValueRow("Format", project.detectedFormat.label)
                KeyValueRow("File Size", "${formatByteSize(project.fileSize)} (${project.fileSize} bytes)")
                KeyValueRow("MIME Type", project.mimeType)
                KeyValueRow("Magic Bytes", project.magicHex, monospaceValue = true)
                KeyValueRow("SHA-256", project.sha256, monospaceValue = true)
                KeyValueRow("SHA-1", project.sha1, monospaceValue = true)
                KeyValueRow("MD5", project.md5, monospaceValue = true)
                KeyValueRow("CRC32", project.crc32, monospaceValue = true)
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.navigateTo(WorkstationDestination.BINARY) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Analyze in Radare2")
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(WorkstationDestination.TOOLS) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open Terminal")
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(WorkstationDestination.HEX) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Hex View")
                    }
                }
            }
        }

        item {
            WorkstationCard(
                title = "Patched Working Copy Log (${state.modifiedEntries.size})",
                subtitle = "Byte modifications recorded in isolated workspace"
            ) {
                if (state.modifiedEntries.isEmpty()) {
                    Text(
                        text = "No byte patches applied yet. Use Hex View to patch working copy bytes.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    state.modifiedEntries.forEach { mod ->
                        KeyValueRow(mod.relativePath, "${mod.changeType} (${mod.modifiedSize} B)", monospaceValue = true)
                    }
                }
            }
        }

        if (state.projects.size > 1) {
            item {
                WorkstationCard(
                    title = "Binary Diff With Another Target",
                    subtitle = "Computes SHA-256 and streaming byte differences"
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.projects.filter { it.id != project.id }.forEach { other ->
                            OutlinedButton(
                                onClick = { viewModel.compareActiveWithOtherProject(other) },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Compare vs ${other.originalFileName}")
                            }
                        }
                    }

                    state.comparisonReport?.let { cmp ->
                        Spacer(modifier = Modifier.height(10.dp))
                        KeyValueRow("Identical SHA-256", cmp.identicalHash.toString())
                        KeyValueRow("Size Delta", "${cmp.sizeA} B vs ${cmp.sizeB} B")
                        KeyValueRow("Differing Bytes", cmp.totalDifferingBytes.toString(), monospaceValue = true)
                    }
                }
            }
        }

        item {
            WorkstationCard(title = "Analyst Notes") {
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reverse-engineering & offset notes") },
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveProjectNotes(notesDraft) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save Notes")
                }
            }
        }
    }
}
