package org.bimz.reforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.terminal.ConsoleEntry
import org.bimz.reforge.ui.WorkstationUiState
import org.bimz.reforge.ui.theme.AmberWarn
import org.bimz.reforge.ui.theme.BorderSubtle
import org.bimz.reforge.ui.theme.CyanAccent
import org.bimz.reforge.ui.theme.EmeraldOk
import org.bimz.reforge.ui.theme.ObsidianBg
import org.bimz.reforge.ui.theme.ObsidianElevated
import org.bimz.reforge.ui.theme.ObsidianSurface
import org.bimz.reforge.ui.theme.RoseDanger
import org.bimz.reforge.ui.theme.TextMuted
import org.bimz.reforge.ui.theme.TextPrimary
import org.bimz.reforge.ui.theme.TextSecondary

@Composable
fun ToolsScreen(
    state: WorkstationUiState,
    onRunCommand: (String) -> Unit,
    onExitR2Mode: () -> Unit,
    onStopCommand: () -> Unit,
    onAddSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onSaveDumpToForge: (String) -> Unit,
    onOpenSavedFolder: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val activeSession = state.activeSession
    val isR2Mode = activeSession.r2Mode
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val promptPrefix = if (isR2Mode) "r2 [${activeSession.currentSeekHex}]>" else "reforge@arm64:~$"
    val promptColor = if (isR2Mode) AmberWarn else CyanAccent

    val quickChips = if (isR2Mode) {
        listOf("aaa", "AA", "afl", "ij", "iSj", "isj", "izz", "pdf @ main")
    } else {
        listOf("r2", "r2 aaa", "r2 afl", "elf", "hash", "engines", "help")
    }

    val lastSnapshotLen = activeSession.history.lastOrNull()?.output?.length ?: 0
    LaunchedEffect(activeSession.id, activeSession.history.size, lastSnapshotLen) {
        if (activeSession.history.isNotEmpty()) {
            listState.animateScrollToItem(activeSession.history.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF070A0F),
            border = BorderStroke(
                1.dp,
                if (isR2Mode) AmberWarn.copy(alpha = 0.5f) else BorderSubtle
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Row 1: Multi-Session Tab Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0F17))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.terminalSessions.forEach { session ->
                            val selected = session.id == activeSession.id
                            val tabBorder = when {
                                selected && session.r2Mode -> AmberWarn.copy(alpha = 0.7f)
                                selected -> CyanAccent.copy(alpha = 0.7f)
                                else -> BorderSubtle
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) ObsidianElevated else ObsidianSurface,
                                border = BorderStroke(1.dp, tabBorder),
                                modifier = Modifier.clickable { onSelectSession(session.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (session.r2Mode) "${session.title} • r2" else session.title,
                                        color = if (selected) (if (session.r2Mode) AmberWarn else CyanAccent) else TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (state.terminalSessions.size > 1) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Close session",
                                            tint = TextMuted,
                                            modifier = Modifier
                                                .size(13.dp)
                                                .clickable { onCloseSession(session.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CyanAccent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable { onAddSession() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "New Session", tint = CyanAccent, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Session", color = CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Row 2: Active Session Status & Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isR2Mode) "r2@${activeSession.currentSeekHex}" else "shell@arm64",
                        color = promptColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (activeSession.isRunning) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = RoseDanger.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, RoseDanger.copy(alpha = 0.55f)),
                            modifier = Modifier.clickable { onStopCommand() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.StopCircle, contentDescription = "Stop", tint = RoseDanger, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("STOP", color = RoseDanger, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (isR2Mode) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AmberWarn.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "PERSISTENT r2",
                                color = AmberWarn,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (isR2Mode) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = RoseDanger.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, RoseDanger.copy(alpha = 0.45f)),
                            modifier = Modifier.clickable { onExitR2Mode() }
                        ) {
                            Text(
                                text = "Exit r2",
                                color = RoseDanger,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = { onSaveDumpToForge("") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SaveAlt,
                            contentDescription = "Save dump to RE FORGE",
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { onRunCommand("clear") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear buffer",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Terminal Buffer
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(activeSession.history, key = { it.id }) { entry ->
                            ConsoleEntryBlock(
                                entry = entry,
                                onCopy = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("r2_output", entry.output))
                                },
                                onSaveDump = {
                                    val payload = "# Command: ${entry.command}\n# Target: ${state.activeFile?.name ?: "none"}\n\n${entry.output}"
                                    onSaveDumpToForge(payload)
                                },
                                onOpenSavedFolder = onOpenSavedFolder
                            )
                        }
                    }
                }

                // Quick Command Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface.copy(alpha = 0.8f))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickChips.forEach { chip ->
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = ObsidianElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isR2Mode) AmberWarn.copy(alpha = 0.35f) else BorderSubtle
                            ),
                            modifier = Modifier.clickable {
                                if (!activeSession.isRunning) {
                                    onRunCommand(chip)
                                }
                            }
                        ) {
                            Text(
                                text = chip,
                                color = if (isR2Mode) AmberWarn else CyanAccent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Prompt Input Line
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianElevated)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = promptPrefix,
                        color = promptColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (input.isEmpty()) {
                            Text(
                                text = if (isR2Mode) "aaa | AA | afl | pdf @ main | exit" else "r2 | elf | hash | save",
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(promptColor),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (input.isNotBlank() && !activeSession.isRunning) {
                                    val cmd = input
                                    input = ""
                                    onRunCommand(cmd)
                                }
                            }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (activeSession.isRunning) {
                        IconButton(
                            onClick = onStopCommand,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.StopCircle,
                                contentDescription = "Stop command",
                                tint = RoseDanger
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (input.isNotBlank()) {
                                    val cmd = input
                                    input = ""
                                    onRunCommand(cmd)
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = "Execute",
                                tint = promptColor
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(74.dp))
    }
}

@Composable
private fun ConsoleEntryBlock(
    entry: ConsoleEntry,
    onCopy: () -> Unit,
    onSaveDump: () -> Unit,
    onOpenSavedFolder: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (entry.command != "session" && entry.command != "target") {
            Text(
                text = if (entry.isR2Command) "r2> ${entry.command}" else "$ ${entry.command}",
                color = if (entry.isR2Command) AmberWarn else CyanAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
        }
        Text(
            text = entry.output,
            color = when {
                entry.isError -> RoseDanger
                entry.isRunning -> AmberWarn
                entry.command == "session" || entry.command == "target" -> EmeraldOk
                else -> TextPrimary
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 16.sp
        )
        if (!entry.isRunning && entry.output.length > 24 && entry.command != "session" && entry.command != "target") {
            Spacer(modifier = Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.clickable { onCopy() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CyanAccent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.clickable { onSaveDump() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save to RE FORGE", color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.clickable { onOpenSavedFolder() }
                ) {
                    Text(
                        text = "Open Vault",
                        color = EmeraldOk,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
