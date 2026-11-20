package org.bimz.reforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bimz.reforge.ui.navigation.ReForgeAppShell
import org.bimz.reforge.ui.state.WorkstationViewModel
import org.bimz.reforge.ui.state.WorkstationViewModelFactory
import org.bimz.reforge.ui.theme.ReForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ReForgeApplication
        setContent {
            val vm: WorkstationViewModel = viewModel(
                factory = WorkstationViewModelFactory(app)
            )
            val uiState by vm.uiState.collectAsState()
            ReForgeTheme(darkTheme = uiState.darkTheme) {
                ReForgeAppShell(viewModel = vm)
            }
        }
    }
}
