package org.bimz.reforge.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Save
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
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@Composable
fun ToolsScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    var commandText by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    var textToSave by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val bottomClearance = LocalBottomClearance.current

    val saveDumpLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val payload = textToSave
        if (uri != null && payload != null) {
            viewModel.exportTextToUri(uri, payload)
            copiedNotice = "Dump saved to device storage."
        }
        textToSave = null
    }

    LaunchedEffect(state.pendingDumpSaveText) {
        val pending = state.pendingDumpSaveText
        if (pending != null) {
            textToSave = pending
            viewModel.clearPendingDumpSave()
            saveDumpLauncher.launch(state.pendingDumpSaveFilename)
        }
    }

    val r2Engine = state.engines.firstOrNull { it.id == "radare2_core" }
    val r2Ready = r2Engine?.enabled == true && r2Engine.initialized
    val inR2Mode = state.r2SessionActive

    val termBg = Color(0xFF090C10)
    val termBarBg = Color(0xFF11161F)
    val termBorder = Color(0xFF263142)
    val promptGreen = Color(0xFF4ADE80)
    val promptR2 = Color(0xFFFBBF24)
    val promptPath = Color(0xFF38BDF8)
    val termStdout = Color(0xFFE2E8F0)
    val termStderr = Color(0xFFF87171)

    LaunchedEffect(state.consoleHistory.size, state.consoleHistory.lastOrNull()?.output, bottomClearance) {
        if (state.consoleHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.consoleHistory.size)
        }
    }

    fun submitCommand(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        copiedNotice = null
        viewModel.runConsoleCommand(trimmed)
        commandText = ""
    }

    fun triggerSaveEntry(commandLabel: String, outputText: String) {
        val baseName = (state.activeProject?.originalFileName ?: "reforge").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeCmd = commandLabel.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
        textToSave = "# Target: ${state.activeProject?.originalFileName ?: "none"}\n# Command: $commandLabel\n\n$outputText"
        saveDumpLauncher.launch("${baseName}_${safeCmd}_dump.txt")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 10.dp,
                end = 10.dp,
                top = 8.dp,
                bottom = bottomClearance
            )
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = termBg,
            border = BorderStroke(1.dp, if (inR2Mode) promptR2.copy(alpha = 0.5f) else termBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Termux / Radare2 Session Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(termBarBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (inR2Mode) "r2@arm64" else "termux@reforge",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (inR2Mode) promptR2 else promptGreen
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(
                            label = if (inR2Mode) "r2 SESSION" else if (r2Ready) "r2 READY" else "r2 OFF",
                            tone = if (inR2Mode) BadgeTone.WARNING else if (r2Ready) BadgeTone.PASS else BadgeTone.NEUTRAL
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inR2Mode) {
                            Surface(
                                onClick = { viewModel.exitR2Session() },
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF291D16),
                                border = BorderStroke(1.dp, promptR2.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "Exit r2",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = promptR2,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(
                            onClick = {
                                if (state.consoleHistory.isNotEmpty()) {
                                    val fullLog = state.consoleHistory.joinToString("\n\n") {
                                        "$ ${it.command}\n${it.output}"
                                    }
                                    triggerSaveEntry("session", fullLog)
                                }
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = "Save all terminal dumps",
                                tint = promptPath
                            )
                        }
                        IconButton(
                            onClick = { viewModel.runConsoleCommand("clear") },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ClearAll,
                                contentDescription = "Clear terminal",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                // Terminal Buffer
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "motd") {
                        Column {
                            Text(
                                text = "Welcome to RE FORGE Terminal (Radare2 ARM64)",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = promptGreen
                            )
                            Text(
                                text = "Target: ${state.activeProject?.originalFileName ?: "none (open from Home)"}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "Tip: Type 'r2' to lock into Radare2 mode, then run 'aaa', 'AA', 'afl', 'pdf @ main', etc.",
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

                    items(state.consoleHistory, key = { it.id }) { entry ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (entry.isR2Command) {
                                    Text(
                                        text = "r2 [0x00000000]> ",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = promptR2
                                    )
                                } else {
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
                                }
                                Text(
                                    text = entry.command,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = entry.output,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                color = when {
                                    entry.isRunning -> promptR2
                                    entry.isError -> termStderr
                                    else -> termStdout
                                }
                            )
                            if (!entry.isRunning && entry.output.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(entry.output))
                                            copiedNotice = "Copied output of '${entry.command}'."
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF161E2B),
                                        border = BorderStroke(1.dp, termBorder)
                                    ) {
                                        Text(
                                            text = "Copy",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            ),
                                            color = Color(0xFF94A3B8),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Surface(
                                        onClick = { triggerSaveEntry(entry.command, entry.output) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF162332),
                                        border = BorderStroke(1.dp, promptPath.copy(alpha = 0.45f))
                                    ) {
                                        Text(
                                            text = "Save Dump",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            ),
                                            color = promptPath,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Dynamic Extra-Keys Bar (switches automatically between Shell keys and Sticky r2 keys)
                val quickKeys = if (inR2Mode) {
                    listOf("aaa", "AA", "afl", "ij", "iSj", "isj", "izz", "pdf @ main", "px 64", "save", "exit")
                } else {
                    listOf("r2", "r2 aaa", "r2 AA", "r2 afl", "r2 ij", "elf", "capstone arm64 0", "hash", "save", "clear")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(termBarBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickKeys.forEach { keyCmd ->
                        Surface(
                            onClick = { submitCommand(keyCmd) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (inR2Mode) Color(0xFF1E1B16) else Color(0xFF1A2230),
                            border = BorderStroke(1.dp, if (inR2Mode) promptR2.copy(alpha = 0.4f) else termBorder),
                            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 38.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = keyCmd,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (inR2Mode) promptR2 else termStdout
                                )
                            }
                        }
                    }
                }

                // Bottom Prompt Input Line (shows `r2 [0x00000000]>` when inside r2 session)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1117))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (inR2Mode) "r2 [0x00000000]> " else "$ ",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (inR2Mode) promptR2 else promptGreen
                    )
                    BasicTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(if (inR2Mode) promptR2 else promptGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitCommand(commandText) }),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .padding(vertical = 10.dp),
                        decorationBox = { innerTextField ->
                            if (commandText.isEmpty()) {
                                Text(
                                    text = if (inR2Mode) "aaa | AA | afl | pdf @ main | exit" else "r2 | elf | hash ...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF64748B)
                                )
                            }
                            innerTextField()
                        }
                    )
                    IconButton(
                        onClick = { submitCommand(commandText) },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = "Execute command",
                            tint = if (inR2Mode) promptR2 else promptGreen
                        )
                    }
                }
            }
        }
    }
}
