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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.terminal.ConsoleEntry
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.navigation.LocalIsKeyboardOpen
import org.bimz.reforge.ui.state.TerminalSessionState
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.ForgeAmber
import org.bimz.reforge.ui.theme.ForgeBg
import org.bimz.reforge.ui.theme.ForgeBorder
import org.bimz.reforge.ui.theme.ForgeCyan
import org.bimz.reforge.ui.theme.ForgeElevated
import org.bimz.reforge.ui.theme.ForgeEmerald
import org.bimz.reforge.ui.theme.ForgeRose
import org.bimz.reforge.ui.theme.ForgeSurface
import org.bimz.reforge.ui.theme.ForgeTextMuted
import org.bimz.reforge.ui.theme.ForgeTextPrimary
import org.bimz.reforge.ui.theme.ForgeTextSecondary

@Composable
fun ToolsScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    var input by remember { mutableStateOf("") }
    val bottomClearance = LocalBottomClearance.current
    val isKeyboardOpen = LocalIsKeyboardOpen.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val activeSession = state.terminalSessions.firstOrNull { it.id == state.activeSessionId }
        ?: state.terminalSessions.firstOrNull()
        ?: TerminalSessionState("session-1", "Session 1", state.consoleHistory)
    val isR2Mode = activeSession.r2Mode
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val promptPrefix = if (isR2Mode) "r2 [${activeSession.currentSeekHex}]>" else "reforge@arm64:~$"
    val promptColor = if (isR2Mode) ForgeAmber else ForgeCyan

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

    val bottomPad = if (isKeyboardOpen) 6.dp else bottomClearance
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeBg)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .padding(bottom = bottomPad)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF070A0F),
            border = BorderStroke(
                1.dp,
                if (isR2Mode) ForgeAmber.copy(alpha = 0.5f) else ForgeBorder
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Multi-Session Tab Bar
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
                                selected && session.r2Mode -> ForgeAmber.copy(alpha = 0.7f)
                                selected -> ForgeCyan.copy(alpha = 0.7f)
                                else -> ForgeBorder
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) ForgeElevated else ForgeSurface,
                                border = BorderStroke(1.dp, tabBorder),
                                modifier = Modifier.clickable { viewModel.selectTerminalSession(session.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (session.r2Mode) "${session.title} • r2" else session.title,
                                        color = if (selected) (if (session.r2Mode) ForgeAmber else ForgeCyan) else ForgeTextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (state.terminalSessions.size > 1) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Close session",
                                            tint = ForgeTextMuted,
                                            modifier = Modifier
                                                .size(13.dp)
                                                .clickable { viewModel.closeTerminalSession(context, session.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ForgeCyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable { viewModel.addTerminalSession() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "New Session", tint = ForgeCyan, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Session", color = ForgeCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Active Session Status Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeSurface)
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
                            color = ForgeRose.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, ForgeRose.copy(alpha = 0.55f)),
                            modifier = Modifier.clickable { viewModel.stopActiveSessionCommand(context) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.StopCircle, contentDescription = "Stop", tint = ForgeRose, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("STOP", color = ForgeRose, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (isR2Mode) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ForgeAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "PERSISTENT r2",
                                color = ForgeAmber,
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
                            color = ForgeRose.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ForgeRose.copy(alpha = 0.45f)),
                            modifier = Modifier.clickable { viewModel.exitActiveR2Session(context) }
                        ) {
                            Text(
                                text = "Exit r2",
                                color = ForgeRose,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = { viewModel.saveDumpToForgeVault(context, "") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SaveAlt,
                            contentDescription = "Save dump to RE FORGE",
                            tint = ForgeCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.executeSessionCommand(context, "clear") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear buffer",
                            tint = ForgeTextSecondary,
                            modifier = Modifier.size(16.dp)
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
                    if (activeSession.history.isEmpty()) {
                        item(key = "empty_hint") {
                            Text(
                                text = "Ready • Target: ${state.activeFile?.name ?: "None"} • Tap a command chip below or type 'r2' / 'help'",
                                color = ForgeTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                    items(activeSession.history, key = { it.id }) { entry ->
                            ConsoleEntryBlock(
                                entry = entry,
                                onCopy = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("r2_output", entry.output))
                                },
                                onSaveDump = {
                                    val payload = "# Command: ${entry.command}\n# Target: ${state.activeFile?.name ?: "none"}\n\n${entry.output}"
                                    viewModel.saveDumpToForgeVault(context, payload)
                                }
                        )
                    }
                }

                // Quick Command Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeSurface.copy(alpha = 0.8f))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickChips.forEach { chip ->
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = ForgeElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isR2Mode) ForgeAmber.copy(alpha = 0.35f) else ForgeBorder
                            ),
                            modifier = Modifier.clickable {
                                if (!activeSession.isRunning) {
                                    viewModel.executeSessionCommand(context, chip)
                                }
                            }
                        ) {
                            Text(
                                text = chip,
                                color = if (isR2Mode) ForgeAmber else ForgeCyan,
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
                        .background(ForgeElevated)
                        .clickable {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
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
                                color = ForgeTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = ForgeTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(promptColor),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (input.isNotBlank() && !activeSession.isRunning) {
                                    val cmd = input
                                    input = ""
                                    viewModel.executeSessionCommand(context, cmd)
                                }
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                    }
                    if (activeSession.isRunning) {
                        IconButton(
                            onClick = { viewModel.stopActiveSessionCommand(context) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.StopCircle,
                                contentDescription = "Stop command",
                                tint = ForgeRose
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (input.isNotBlank()) {
                                    val cmd = input
                                    input = ""
                                    viewModel.executeSessionCommand(context, cmd)
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
    }
}

@Composable
private fun ConsoleEntryBlock(
    entry: ConsoleEntry,
    onCopy: () -> Unit,
    onSaveDump: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (entry.command != "session" && entry.command != "target") {
            Text(
                text = if (entry.isR2Command) "r2> ${entry.command}" else "$ ${entry.command}",
                color = if (entry.isR2Command) ForgeAmber else ForgeCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
        }
        SelectionContainer {
            Text(
                text = entry.output,
                color = when {
                    entry.isError -> ForgeRose
                    entry.isRunning -> ForgeAmber
                    entry.command == "session" || entry.command == "target" -> ForgeEmerald
                    else -> ForgeTextPrimary
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
        if (!entry.isRunning && entry.output.length > 24 && entry.command != "session" && entry.command != "target") {
            Spacer(modifier = Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForgeSurface,
                    border = BorderStroke(1.dp, ForgeBorder),
                    modifier = Modifier.clickable { onCopy() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = ForgeTextSecondary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", color = ForgeTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForgeCyan.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.clickable { onSaveDump() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save to RE FORGE", color = ForgeCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
