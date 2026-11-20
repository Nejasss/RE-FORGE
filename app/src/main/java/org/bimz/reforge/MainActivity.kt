package org.bimz.reforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bimz.reforge.ui.WorkstationViewModel
import org.bimz.reforge.ui.navigation.ReForgeNav
import org.bimz.reforge.ui.theme.ReForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReForgeTheme {
                val vm: WorkstationViewModel = viewModel()

                val openFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        vm.importFileFromUri(uri)
                    }
                }

                val importToForgeLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        vm.importFileDirectToForge(uri)
                    }
                }

                ReForgeNav(
                    viewModel = vm,
                    onOpenFilePicker = {
                        openFileLauncher.launch(arrayOf("*/*"))
                    },
                    onImportToForgePicker = {
                        importToForgeLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }
}
