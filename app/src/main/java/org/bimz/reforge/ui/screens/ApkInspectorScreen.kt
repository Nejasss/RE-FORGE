package org.bimz.reforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.state.WorkstationDestination
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApkInspectorScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    val report = state.apkReport
    if (state.activeProject == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "No APK Loaded",
                    reason = "Import an APK package from Home to decode AndroidManifest.xml, components, signatures, and resources.",
                    actionLabel = "Go to Home",
                    onAction = { viewModel.navigateTo(WorkstationDestination.HOME) }
                )
            }
        }
        return
    }

    if (report == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            item {
                EmptyActionState(
                    title = "APK Report Not Generated",
                    reason = "Run the APK Inspector on ${state.activeProject.originalFileName} to decode AXML and verify signatures.",
                    actionLabel = "Run APK Inspector",
                    onAction = { viewModel.runApkInspection() }
                )
            }
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val tabs = listOf("Overview", "Components", "Entries", "Manifest XML", "Resources")

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
                tabs.forEachIndexed { idx, label ->
                    FilterChip(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        label = { Text(label) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                item {
                    WorkstationCard(
                        title = report.applicationLabel,
                        subtitle = report.packageName
                    ) {
                        StatusPill(
                            label = if (report.signature.isSigned) "Signature Verified" else "Unsigned / Invalid Signature",
                            tone = if (report.signature.isSigned) BadgeTone.PASS else BadgeTone.WARNING
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyValueRow("Version", "${report.versionName} (${report.versionCode})")
                        KeyValueRow("SDK Levels", "minSdk ${report.minSdkVersion} / targetSdk ${report.targetSdkVersion}")
                        KeyValueRow("Debuggable", report.debuggable.toString())
                        KeyValueRow("Allow Backup", report.allowBackup.toString())
                        KeyValueRow("Native ABIs", report.nativeAbis.joinToString(", ").ifEmpty { "none" }, monospaceValue = true)
                        KeyValueRow("DEX Files", report.dexFiles.joinToString(", "), monospaceValue = true)
                        KeyValueRow("Sig Schemes", "v1=${report.signature.v1Scheme}, v2=${report.signature.v2Scheme}, v3=${report.signature.v3Scheme}")
                        report.signature.signerSubjects.forEach { subj ->
                            KeyValueRow("Signer DN", subj, monospaceValue = true)
                        }
                        report.signature.certSha256List.forEach { certHash ->
                            KeyValueRow("Cert SHA-256", certHash, monospaceValue = true)
                        }
                    }
                }
                item {
                    WorkstationCard(
                        title = "Declared Permissions (${report.permissions.size})"
                    ) {
                        if (report.permissions.isEmpty()) {
                            Text("No <uses-permission> tags declared.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            report.permissions.forEach { perm ->
                                Text(
                                    text = perm,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            1 -> {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Filter components by name or type") },
                        singleLine = true
                    )
                }
                val filtered = report.components.filter {
                    searchQuery.isBlank() ||
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.type.contains(searchQuery, ignoreCase = true)
                }
                items(filtered) { comp ->
                    WorkstationCard(
                        title = comp.name.substringAfterLast('.'),
                        subtitle = "${comp.type} | ${comp.name}"
                    ) {
                        KeyValueRow("Exported", comp.exported?.toString() ?: "implicit")
                        comp.permission?.let { perm ->
                            KeyValueRow("Permission", perm)
                        }
                        if (comp.intentActions.isNotEmpty()) {
                            KeyValueRow("Actions", comp.intentActions.joinToString(", "))
                        }
                    }
                }
            }

            2 -> {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Filter ${report.entries.size} APK archive entries") },
                        singleLine = true
                    )
                }
                val filteredEntries = report.entries.filter {
                    searchQuery.isBlank() || it.path.contains(searchQuery, ignoreCase = true)
                }.take(250)
                items(filteredEntries) { entry ->
                    WorkstationCard(
                        title = entry.path,
                        subtitle = "${entry.category} | ${formatByteSize(entry.uncompressedSize)} (compressed ${formatByteSize(entry.compressedSize)})"
                    ) {
                        KeyValueRow("CRC32", entry.crc32.toString(16), monospaceValue = true)
                    }
                }
            }

            3 -> {
                item {
                    WorkstationCard(
                        title = "Decoded AndroidManifest.xml",
                        subtitle = "Reconstructed from binary AXML chunks"
                    ) {
                        Text(
                            text = report.decodedManifestXml,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }

            4 -> {
                if (state.selectedResourcePath != null) {
                    item {
                        WorkstationCard(
                            title = state.selectedResourcePath,
                            subtitle = "Decoded resource preview"
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.inspectResourceEntry("") },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Back to Resource List")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.selectedResourceText,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                } else {
                    items(state.resourceEntries.take(200)) { resNode ->
                        WorkstationCard(
                            title = resNode.path,
                            subtitle = "${resNode.group} | ${formatByteSize(resNode.sizeBytes)}"
                        ) {
                            Button(
                                onClick = { viewModel.inspectResourceEntry(resNode.path) },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Inspect Resource")
                            }
                        }
                    }
                }
            }
        }
    }
}
