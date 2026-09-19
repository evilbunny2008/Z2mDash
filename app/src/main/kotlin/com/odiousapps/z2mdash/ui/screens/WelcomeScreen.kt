package com.odiousapps.z2mdash.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.BackupCodec

/**
 * Shown instead of an empty Home screen when there are no brokers configured yet - offers Add a
 * Broker or restoring a previous backup, rather than assuming a fresh start.
 */
@Composable
fun WelcomeScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val context = LocalContext.current

    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val bytes = input.readBytes()
                    val text = bytes.toString(Charsets.UTF_8)
                    // Same three formats SettingsScreen's import handles: base64+gzip (current),
                    // raw gzip (earlier), or plain JSON (oldest, pre-compression).
                    val json = try {
                        BackupCodec.decompressFromBase64(text)
                    } catch (_: Exception) {
                        try {
                            BackupCodec.decompress(bytes)
                        } catch (_: Exception) {
                            text
                        }
                    }
                    app.configRepository.importJson(json)
                    navController.popBackStack()
                }
            } catch (_: Exception) {
                errorMessage = "Import failed: not a valid config file"
            }
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Welcome", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "You'll need at least one MQTT broker to get started \u2013 add one now, " +
                    "or restore a config you've previously backed up.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { navController.navigate("broker/new") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add a Broker") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    try {
                        importLauncher.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        errorMessage = "No file picker app is available on this device."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore from Backup") }
        }
    }
}
