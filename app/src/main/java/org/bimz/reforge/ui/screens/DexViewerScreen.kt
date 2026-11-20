package org.bimz.reforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DexViewerScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    if (state.activeProject == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "No DEX or APK Loaded",
                    reason = "Import an APK or DEX file from Home to inspect classes, Smali disassembly, and Java decompilation.",
                    actionLabel = "Go to Home",
                    onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
                )
            }
        }
        return
    }

    if (state.dexClasses.isEmpty()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "DEX Index Empty",
                    reason = "Index Dalvik classes from ${state.activeProject.originalFileName} using dexlib2.",
                    actionLabel = "Index DEX Classes",
                    onAction = { viewModel.runDexIndexing() }
                )
            }
        }
        return
    }

    var classFilter by remember { mutableStateOf("") }
    var codeTab by remember { mutableIntStateOf(0) } // 0 = Smali Editor, 1 = Diff, 2 = JADX Java

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WorkstationCard(
                title = "DEX Containers (${state.dexContainers.size})",
                subtitle = "Total indexed classes: ${state.dexClasses.size}"
            ) {
                state.dexContainers.forEach { c ->
                    KeyValueRow(
                        c.dexFileName,
                        "${c.dexVersion} | ${c.classCount} classes | ${c.methodCount} methods"
                    )
                }
            }
        }

        val selected = state.selectedDexClass
        if (selected != null) {
            item {
                WorkstationCard(
                    title = selected.javaName,
                    subtitle = "${selected.dexEntryName} | Super: ${selected.superClass ?: "none"}"
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = codeTab == 0,
                            onClick = { codeTab = 0 },
                            label = { Text("Smali Editor") },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        )
                        FilterChip(
                            selected = codeTab == 1,
                            onClick = { codeTab = 1 },
                            label = { Text("Smali Diff") },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        )
                        FilterChip(
                            selected = codeTab == 2,
                            onClick = {
                                codeTab = 2
                                if (state.decompiledJavaText.isBlank()) {
                                    viewModel.decompileSelectedClassWithJadx()
                                }
                            },
                            label = { Text("JADX Java") },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        )
                        OutlinedButton(
                            onClick = { viewModel.saveStagedSmaliEdit() },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Stage Smali Edit")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    when (codeTab) {
                        0 -> {
                            OutlinedTextField(
                                value = state.editedSmaliText,
                                onValueChange = { viewModel.updateEditedSmali(it) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                label = { Text("Smali assembly (editable in workspace)") },
                                minLines = 12
                            )
                        }
                        1 -> {
                            Text(
                                text = state.smaliDiffText.ifBlank { "No differences." },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                        2 -> {
                            if (state.decompilerDisclaimer.isNotBlank()) {
                                StatusPill(label = "Bytecode Approximation (JADX)", tone = BadgeTone.WARNING)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.decompilerDisclaimer,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (state.decompiledJavaText.isBlank()) {
                                Button(
                                    onClick = { viewModel.decompileSelectedClassWithJadx() },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Decompile Selected Class")
                                }
                            } else {
                                Text(
                                    text = state.decompiledJavaText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = classFilter,
                onValueChange = { classFilter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search class or package name") },
                singleLine = true
            )
        }

        val matchingClasses = state.dexClasses.filter {
            classFilter.isBlank() || it.javaName.contains(classFilter, ignoreCase = true)
        }.take(150)

        items(matchingClasses, key = { "${it.dexEntryName}:${it.descriptor}" }) { cls ->
            WorkstationCard(
                title = cls.javaName.substringAfterLast('.'),
                subtitle = "${cls.packageName} | ${cls.methods.size} methods, ${cls.fields.size} fields"
            ) {
                Button(
                    onClick = { viewModel.selectDexClass(cls) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Inspect Smali & Java")
                }
            }
        }
    }
}
