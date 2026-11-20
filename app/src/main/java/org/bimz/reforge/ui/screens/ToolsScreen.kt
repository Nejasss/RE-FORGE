package org.bimz.reforge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@Composable
fun ToolsScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    var commandText by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val r2Engine = state.engines.firstOrNull { it.id == "radare2_core" }
    val r2Ready = r2Engine?.enabled == true && r2Engine.initialized

    val termBg = Color(0xFF090C10)
    val termBarBg = Color(0xFF11161F)
    val termBorder = Color(0xFF263142)
    val promptGreen = Color(0xFF4ADE80)
    val promptPath = Color(0xFF38BDF8)
    val termStdout = Color(0xFFE2E8F0)
    val termStderr = Color(0xFFF87171)

    LaunchedEffect(state.consoleHistory.size) {
        if (state.consoleHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.consoleHistory.size - 1)
        }
    }

    fun submitCommand(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        copiedNotice = null
        viewModel.runConsoleCommand(trimmed)
        commandText = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Terminal Container
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = termBg,
            border = BorderStroke(1.dp, termBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Termux Session Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(termBarBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "termux@reforge",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = promptGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(
                            label = if (r2Ready) "r2 UNLOCKED" else "r2 OFF/ABSENT",
                            tone = if (r2Ready) BadgeTone.PASS else BadgeTone.WARNING
                        )
                    }
                    IconButton(
                        onClick = { viewModel.runConsoleCommand("clear") },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ClearAll,
                            contentDescription = "Clear terminal",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }

                // Terminal Buffer
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "motd") {
                        Column {
                            Text(
                                text = "Welcome to RE FORGE Terminal (ARM64)",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = promptGreen
                            )
                            Text(
                                text = "Target: ${state.activeProject?.originalFileName ?: "none (load from Home)"}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "Commands: help | engines | hash | elf | capstone <arch> <off> | r2 <cmd> | clear",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF94A3B8)
                            )
                            if (copiedNotice != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = copiedNotice.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = promptPath
                                )
                            }
                        }
                    }

                    items(state.consoleHistory) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(entry.output))
                                    copiedNotice = "Copied output of '${entry.command}' to clipboard."
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "~/workspace ",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = promptPath
                                )
                                Text(
                                    text = "$ ",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = promptGreen
                                )
                                Text(
                                    text = entry.command,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = entry.output,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                color = if (entry.isError) termStderr else termStdout
                            )
                        }
                    }
                }

                // Termux Extra-Keys Quick Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(termBarBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "help",
                        "r2 aaa; afl",
                        "r2 ij",
                        "r2 iSj",
                        "r2 isj",
                        "r2 izz",
                        "r2 pd 64",
                        "elf",
                        "capstone arm64 0",
                        "hash",
                        "clear"
                    ).forEach { keyCmd ->
                        Surface(
                            onClick = { submitCommand(keyCmd) },
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1A2230),
                            border = BorderStroke(1.dp, termBorder),
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = keyCmd,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = termStdout
                                )
                            }
                        }
                    }
                }

                // Bottom Prompt Input Line
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1117))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$ ",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = promptGreen
                    )
                    BasicTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(promptGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitCommand(commandText) }),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .padding(vertical = 10.dp),
                        decorationBox = { innerTextField ->
                            if (commandText.isEmpty()) {
                                Text(
                                    text = "Enter command (e.g. r2 aaa; afl)...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF64748B)
                                )
                            }
                            innerTextField()
                        }
                    )
                    IconButton(
                        onClick = { submitCommand(commandText) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = "Execute command",
                            tint = promptGreen
                        )
                    }
                }
            }
        }
    }
}
