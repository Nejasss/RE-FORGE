package org.bimz.reforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bimz.reforge.storage.SavedForgeFile
import org.bimz.reforge.ui.navigation.LocalBottomClearance
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.theme.ForgeBg
import org.bimz.reforge.ui.theme.ForgeBorder
import org.bimz.reforge.ui.theme.ForgeCyan
import org.bimz.reforge.ui.theme.ForgeEmerald
import org.bimz.reforge.ui.theme.ForgeRose
import org.bimz.reforge.ui.theme.ForgeSurface
import org.bimz.reforge.ui.theme.ForgeTextMuted
import org.bimz.reforge.ui.theme.ForgeTextPrimary
import org.bimz.reforge.ui.theme.ForgeTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SavedFilesScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToHex: () -> Unit = {}
) {
    val context = LocalContext.current
    val bottomClearance = LocalBottomClearance.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFileDirectToForge(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSavedForgeFiles(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = ForgeSurface,
            border = BorderStroke(1.dp, ForgeBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderSpecial,
                    contentDescription = null,
                    tint = ForgeCyan,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RE FORGE Vault",
                        color = ForgeTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Download/RE FORGE • ${state.savedForgeFiles.size} file(s)",
                        color = ForgeTextSecondary,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForgeCyan,
                        contentColor = ForgeBg
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.savedForgeFiles.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = ForgeSurface,
                border = BorderStroke(1.dp, ForgeBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Folder RE FORGE masih kosong",
                        color = ForgeTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Hasil 'dump' / 'Save to RE FORGE' dari terminal Radare2 maupun binary yang di-import akan tersimpan otomatis di sini dan bisa langsung dibuka.",
                        color = ForgeTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = bottomClearance + 20.dp)
            ) {
                items(state.savedForgeFiles, key = { it.file.absolutePath }) { item ->
                    SavedForgeFileCard(
                        item = item,
                        onReadDump = { viewModel.openDumpViewer(item, compact = false) },
                        onOpenTerminal = {
                            viewModel.openSavedForgeFileInWorkstation(item.file)
                            onNavigateToTerminal()
                        },
                        onOpenHex = {
                            viewModel.openSavedForgeFileInWorkstation(item.file)
                            onNavigateToHex()
                        },
                        onDelete = { viewModel.deleteSavedForgeFile(context, item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedForgeFileCard(
    item: SavedForgeFile,
    onReadDump: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenHex: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.US) }
    val badgeColor = if (item.isTextDump) ForgeEmerald else ForgeCyan
    val badgeText = if (item.isTextDump) "DUMP" else "BINARY"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ForgeSurface,
        border = BorderStroke(1.dp, ForgeBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.name,
                    color = ForgeTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        tint = ForgeRose,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatFileBytes(item.sizeBytes)} • ${dateFmt.format(Date(item.lastModifiedMs))}",
                color = ForgeTextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.isTextDump) {
                    Button(
                        onClick = onReadDump,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForgeEmerald,
                            contentColor = ForgeBg
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Open Dump", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenHex,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeTextPrimary),
                        border = BorderStroke(1.dp, ForgeBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.GridOn, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Hex", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onOpenTerminal,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForgeCyan,
                            contentColor = ForgeBg
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Open in r2", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenHex,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeTextPrimary),
                        border = BorderStroke(1.dp, ForgeBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(Icons.Outlined.GridOn, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Open in Hex", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatFileBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
