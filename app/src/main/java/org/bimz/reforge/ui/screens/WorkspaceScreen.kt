package org.bimz.reforge.ui.screens

import android.content.Intent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.bimz.reforge.core.model.CheckStatus
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.EmptyActionState
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val context = LocalContext.current
    val project = state.activeProject
    if (project == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "No Active Project Selected",
                    reason = "Open or import a file from the Home tab to inspect metadata, stage patches, rebuild, or sign.",
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
            }
        }

        item {
            WorkstationCard(
                title = "Build, Sign & Verify Pipeline",
                subtitle = "Authorized local APK packaging with 16KB native .so alignment and apksig v1/v2/v3"
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.rebuildActiveApk() },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Rebuild APK")
                    }
                    Button(
                        onClick = { viewModel.signRebuiltOrWorkingApk() },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Sign & Verify APK")
                    }
                    val signedPath = state.signingResult?.signedApkPath
                    if (signedPath != null && File(signedPath).exists()) {
                        OutlinedButton(
                            onClick = {
                                val file = File(signedPath)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.android.package-archive"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export Signed APK"))
                            },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Export Signed APK")
                        }
                    }
                }

                state.rebuildResult?.let { res ->
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusPill(
                        label = "Rebuild: ${res.status} (${res.changedFileCount} changes)",
                        tone = if (res.outputApkPath != null) BadgeTone.PASS else BadgeTone.FAILED
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = res.buildLog,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }

                state.signingResult?.let { sig ->
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusPill(
                        label = if (sig.success) "Signed: ${sig.signerAlias}" else "Signing Failed",
                        tone = if (sig.success) BadgeTone.PASS else BadgeTone.FAILED
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = sig.message, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Text(
                        text = sig.updateIdentityWarning,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.verificationReport?.let { ver ->
                    Spacer(modifier = Modifier.height(10.dp))
                    val tone = when (ver.overallStatus) {
                        CheckStatus.PASS -> BadgeTone.PASS
                        CheckStatus.WARNING -> BadgeTone.WARNING
                        CheckStatus.FAILED -> BadgeTone.FAILED
                        CheckStatus.NOT_TESTED -> BadgeTone.NEUTRAL
                    }
                    StatusPill(label = "Post-Build Verification: ${ver.overallStatus}", tone = tone)
                    Spacer(modifier = Modifier.height(6.dp))
                    ver.items.forEach { item ->
                        KeyValueRow(item.checkName, "[${item.status}] ${item.detail}", monospaceValue = true)
                    }
                }
            }
        }

        item {
            WorkstationCard(
                title = "Staged Workspace Modifications (${state.modifiedEntries.size})",
                subtitle = "Tracked edits in staging/ before rebuild"
            ) {
                if (state.modifiedEntries.isEmpty()) {
                    Text(
                        text = "No staged edits recorded yet. Edit Smali in DEX view or bytes in Hex view.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
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
                    title = "Compare With Another Workspace",
                    subtitle = "Computes SHA-256, ZIP entry delta, and streaming byte differences"
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.projects.filter { it.id != project.id }.forEach { other ->
                            OutlinedButton(
                                onClick = { viewModel.compareActiveWithOtherProject(other) },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                shape = RoundedCornerShape(6.dp)
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
                        KeyValueRow("Added Entries", cmp.addedZipEntries.size.toString())
                        KeyValueRow("Removed Entries", cmp.removedZipEntries.size.toString())
                        KeyValueRow("Modified Entries", cmp.modifiedZipEntries.size.toString())
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
                    label = { Text("Research and audit notes") },
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveProjectNotes(notesDraft) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Save Notes")
                }
            }
        }
    }
}
