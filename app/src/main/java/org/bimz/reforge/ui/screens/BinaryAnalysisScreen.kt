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
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BinaryAnalysisScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    if (state.activeProject == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "No Binary Target Loaded",
                    reason = "Import an ELF executable, .so shared library, or raw binary from Home.",
                    actionLabel = "Go to Home",
                    onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
                )
            }
        }
        return
    }

    var activeTab by remember { mutableIntStateOf(0) }
    var archInput by remember(state.disasmArch) { mutableStateOf(state.disasmArch) }
    var offsetInput by remember(state.disasmOffsetHex) { mutableStateOf(state.disasmOffsetHex) }
    var symbolSearch by remember { mutableStateOf("") }

    val tabs = listOf("ELF Overview", "Sections", "Symbols", "Strings", "Disassembly", "Radare2")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    FilterChip(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        label = { Text(title) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    )
                }
            }
        }

        val elf = state.elfReport
        when (activeTab) {
            0 -> {
                if (elf == null || !elf.valid) {
                    item {
                        EmptyActionState(
                            title = "ELF Header Not Parsed",
                            reason = elf?.error ?: "Run the native C++ ELF parser on ${state.activeProject.originalFileName}.",
                            actionLabel = "Parse ELF Binary",
                            onAction = { viewModel.runElfInspection() }
                        )
                    }
                } else {
                    item {
                        WorkstationCard(
                            title = elf.architectureName,
                            subtitle = "${elf.bitWidth}-bit | ${if (elf.littleEndian) "Little-Endian" else "Big-Endian"}"
                        ) {
                            KeyValueRow("Entry Point", "0x${elf.entryPoint.toString(16)}", monospaceValue = true)
                            KeyValueRow("Segments", elf.segments.size.toString())
                            KeyValueRow("Sections", elf.sections.size.toString())
                            KeyValueRow("Symbols", elf.symbols.size.toString())
                            KeyValueRow(
                                "DT_NEEDED",
                                elf.dependencies.joinToString(", ").ifEmpty { "none" },
                                monospaceValue = true
                            )
                        }
                    }
                    items(elf.segments) { seg ->
                        WorkstationCard(
                            title = "Segment #${seg.index}: ${seg.typeName}",
                            subtitle = "vaddr=0x${seg.virtualAddress.toString(16)} | offset=0x${seg.fileOffset.toString(16)}"
                        ) {
                            KeyValueRow("Sizes", "file=${seg.fileSize} B | mem=${seg.memSize} B | align=${seg.alignment}", monospaceValue = true)
                        }
                    }
                }
            }

            1 -> {
                val sections = elf?.sections.orEmpty()
                if (sections.isEmpty()) {
                    item {
                        EmptyActionState(
                            title = "No ELF Sections",
                            reason = "Parse a valid ELF binary to inspect section headers.",
                            actionLabel = "Run ELF Parser",
                            onAction = { viewModel.runElfInspection() }
                        )
                    }
                } else {
                    items(sections) { sec ->
                        WorkstationCard(
                            title = sec.name.ifBlank { "<unnamed>" },
                            subtitle = "offset=0x${sec.fileOffset.toString(16)} | size=${sec.size} bytes | vaddr=0x${sec.virtualAddress.toString(16)}"
                        ) {
                            OutlinedButton(
                                onClick = {
                                    offsetInput = sec.fileOffset.toString(16)
                                    activeTab = 4
                                    viewModel.runCapstoneDisassembly(archInput, offsetInput)
                                },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Disassemble Section")
                            }
                        }
                    }
                }
            }

            2 -> {
                item {
                    OutlinedTextField(
                        value = symbolSearch,
                        onValueChange = { symbolSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Filter ${elf?.symbols?.size ?: 0} ELF symbols") },
                        singleLine = true
                    )
                }
                val syms = elf?.symbols.orEmpty().filter {
                    symbolSearch.isBlank() || it.name.contains(symbolSearch, ignoreCase = true)
                }.take(200)
                items(syms) { sym ->
                    WorkstationCard(
                        title = sym.name,
                        subtitle = "${if (sym.isImport) "IMPORT (UND)" else "EXPORT/LOCAL"} | addr=0x${sym.value.toString(16)} | size=${sym.size}"
                    ) {}
                }
            }

            3 -> {
                val strings = elf?.strings.orEmpty().take(250)
                items(strings) { strItem ->
                    WorkstationCard(
                        title = strItem.value,
                        subtitle = "0x${strItem.offset.toString(16)} (${strItem.section})"
                    ) {}
                }
            }

            4 -> {
                item {
                    WorkstationCard(
                        title = "Capstone Native Disassembler",
                        subtitle = "Decodes native machine instructions directly from binary file offset"
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("arm64", "arm", "x86_64", "x86").forEach { a ->
                                FilterChip(
                                    selected = archInput == a,
                                    onClick = { archInput = a },
                                    label = { Text(a.uppercase()) },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = offsetInput,
                            onValueChange = { offsetInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Hex file offset (e.g. 1000)") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.runCapstoneDisassembly(archInput, offsetInput) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Disassemble Offset")
                        }
                    }
                }
                items(state.instructions) { ins ->
                    Text(
                        text = "0x${ins.address.toString(16).padStart(8, '0')}  ${ins.bytes.padEnd(14)}  ${ins.mnemonic} ${ins.opStr}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            5 -> {
                item {
                    WorkstationCard(
                        title = "Radare2 ARM64 Analysis Bridge",
                        subtitle = "Executes non-interactive allowlisted Radare2 queries"
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("ij", "iSj", "isj", "iij", "aflj", "pd 32").forEach { q ->
                                OutlinedButton(
                                    onClick = { viewModel.runRadare2Query(q) },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("r2 $q")
                                }
                            }
                        }
                        if (state.r2Output.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = state.r2Output,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
}
