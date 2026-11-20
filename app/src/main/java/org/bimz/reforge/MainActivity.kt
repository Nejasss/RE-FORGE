package org.bimz.reforge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import org.bimz.reforge.service.ReForgeSessionService
import org.bimz.reforge.ui.navigation.ReForgeAppShell
import org.bimz.reforge.ui.theme.ReForgeTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        ReForgeSessionService.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        ReForgeSessionService.sync(this)

        val app = application as ReForgeApplication
        setContent {
            val vm = app.sharedViewModel
            val uiState by vm.uiState.collectAsState()
            ReForgeTheme(darkTheme = uiState.darkTheme) {
                ReForgeAppShell(viewModel = vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        ReForgeSessionService.sync(this)
    }
}
