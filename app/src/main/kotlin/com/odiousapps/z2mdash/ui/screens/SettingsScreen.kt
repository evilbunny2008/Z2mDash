package com.odiousapps.z2mdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication

@Composable
fun SettingsScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("Brokers") },
                    supportingContent = { Text("${config.brokers.size} configured") },
                    leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("brokers") }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Background Work") },
                    supportingContent = { Text("Keep broker connections alive when the app is closed") },
                    leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = config.backgroundWorkEnabled,
                            onCheckedChange = { enabled ->
                                app.configRepository.update { it.copy(backgroundWorkEnabled = enabled) }
                            }
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Blink Warnings") },
                    supportingContent = { Text("Pulse out-of-range sensor tile alerts, instead of a static color") },
                    leadingContent = { Icon(Icons.Default.Warning, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = config.staleDataBlinkEnabled,
                            onCheckedChange = { enabled ->
                                app.configRepository.update { it.copy(staleDataBlinkEnabled = enabled) }
                            }
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Alarm/Alert") },
                    supportingContent = { Text("Smoke alerts, sound, and a test notification") },
                    leadingContent = { Icon(Icons.Default.Warning, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("alertSettings") }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Backup & Restore") },
                    supportingContent = { Text("Save or restore your configuration, full or brokers only") },
                    leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("backupRestore") }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("About") },
                    supportingContent = { Text("Z2M Dash \u2013 no cloud, no account, no lock-in") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
            }
        }
    }
}
