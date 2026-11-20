package org.bimz.reforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.ForgeBg
import org.bimz.reforge.ui.theme.ForgeBorder
import org.bimz.reforge.ui.theme.ForgeCyan
import org.bimz.reforge.ui.theme.ForgeElevated
import org.bimz.reforge.ui.theme.ForgeSurface
import org.bimz.reforge.ui.theme.ForgeTextMuted
import org.bimz.reforge.ui.theme.ForgeTextPrimary
import org.bimz.reforge.ui.theme.ForgeTextSecondary

@Composable
fun WorkspaceScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val context = LocalContext.current
    val bottomClearance = LocalBottomClearance.current
    val project = state.activeProject
    if (project == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ForgeBg)
                .padding(14.dp)
        ) {
            EmptyActionState(
                title = "No Active Target Selected",
                reason = "Select or import a binary from Home to inspect hashes, compare files, and store notes.",
                actionLabel = "Go to Home",
                onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
            )
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var notesDraft by remember(project.id) { mutableStateOf(project.notes) }
    val tabs = listOf("Overview", "Diff (${state.projects.size - 1})", "Notes")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeBg)
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomClearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ForgeSurface,
                border = BorderStroke(1.dp, ForgeBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = project.originalFileName,
                        color = ForgeTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${project.detectedFormat.label} • ${formatByteSize(project.fileSize)} (${project.fileSize} B)",
                        color = ForgeTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.navigateTo(WorkstationDestination.TOOLS) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ForgeCyan,
                                contentColor = ForgeBg
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                        ) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Terminal", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.navigateTo(WorkstationDestination.HEX) },
                            border = BorderStroke(1.dp, ForgeBorder),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                        ) {
                            Icon(Icons.Outlined.GridOn, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hex View", color = ForgeTextPrimary, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeSurface, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    val active = selectedTab == idx
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) ForgeElevated else ForgeSurface,
                        border = if (active) BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.55f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clickable { selectedTab = idx }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                color = if (active) ForgeCyan else ForgeTextSecondary,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ForgeSurface,
                        border = BorderStroke(1.dp, ForgeBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Hashes & File Signature",
                                    color = ForgeTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ForgeElevated,
                                    border = BorderStroke(1.dp, ForgeBorder),
                                    modifier = Modifier.clickable {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("SHA-256", project.sha256))
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = ForgeCyan, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Copy SHA-256", color = ForgeCyan, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            KeyValueRow("MIME Type", project.mimeType)
                            KeyValueRow("Magic", project.magicHex.ifBlank { "n/a" }, monospaceValue = true)
                            KeyValueRow("SHA-256", project.sha256, monospaceValue = true)
                            KeyValueRow("SHA-1", project.sha1, monospaceValue = true)
                            KeyValueRow("MD5", project.md5, monospaceValue = true)
                            KeyValueRow("CRC32", project.crc32, monospaceValue = true)
                            KeyValueRow("Working Patches", "${state.modifiedEntries.size} file(s)", monospaceValue = true)
                        }
                    }
                }
            }

            1 -> {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ForgeSurface,
                        border = BorderStroke(1.dp, ForgeBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Compare Binary Against Other Target",
                                color = ForgeTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val others = state.projects.filter { it.id != project.id }
                            if (others.isEmpty()) {
                                Text(
                                    text = "Import at least 2 binaries to run a byte-level diff.",
                                    color = ForgeTextMuted,
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    others.forEach { other ->
                                        OutlinedButton(
                                            onClick = { viewModel.compareActiveWithOtherProject(other) },
                                            border = BorderStroke(1.dp, ForgeBorder),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 42.dp)
                                        ) {
                                            Text("Compare vs ${other.originalFileName}", color = ForgeTextPrimary, fontSize = 12.sp)
                                        }
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
            }

            2 -> {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ForgeSurface,
                        border = BorderStroke(1.dp, ForgeBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Analyst Notes",
                                color = ForgeTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = notesDraft,
                                onValueChange = { notesDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Offsets, functions, & patch notes") },
                                minLines = 4
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.saveProjectNotes(notesDraft) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ForgeCyan,
                                    contentColor = ForgeBg
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 44.dp)
                            ) {
                                Text("Save Notes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
