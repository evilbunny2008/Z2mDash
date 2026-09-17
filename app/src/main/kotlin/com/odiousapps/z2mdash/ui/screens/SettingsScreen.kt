package com.odiousapps.z2mdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication

@Composable
fun SettingsScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()
    var showPruneConfirm by remember { mutableStateOf(false) }
    var pruneResultMessage by remember { mutableStateOf<String?>(null) }
    // A panel left tagged with a brokerId that no longer matches any
    // configured broker can never update again - most commonly left behind
    // by deleting a broker before ConfigRepository.deleteBroker() cleaned
    // this up itself (see that function's own comment), so re-adding the
    // same broker afterwards doubled up every cluster instead of just
    // reconnecting to the same one.
    val orphanedPanelCount = remember(config) {
        val brokerIds = config.brokers.map { it.id }.toSet()
        config.groups.sumOf { g -> g.panels.count { it.brokerId !in brokerIds } }
    }

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
                    headlineContent = { Text("Tile & Cluster Width") },
                    supportingContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "How wide tiles (and so cluster cards, 3 tiles wide) can grow, up to " +
                                    "filling the screen - currently ${config.tileWidthDp}dp"
                            )
                            Slider(
                                value = config.tileWidthDp.toFloat(),
                                onValueChange = { newValue ->
                                    app.configRepository.update { it.copy(tileWidthDp = newValue.toInt()) }
                                },
                                valueRange = 80f..200f,
                                steps = 11 // 10dp increments from 80 to 200
                            )
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Straighten, contentDescription = null) }
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
            if (orphanedPanelCount > 0) {
                item {
                    ListItem(
                        headlineContent = { Text("Clean Up Orphaned Data") },
                        supportingContent = {
                            Text("$orphanedPanelCount panel(s) left over from a deleted broker can be removed")
                        },
                        leadingContent = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                        modifier = Modifier.clickable { showPruneConfirm = true }
                    )
                }
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

    if (showPruneConfirm) {
        AlertDialog(
            onDismissRequest = { showPruneConfirm = false },
            title = { Text("Clean up orphaned data?") },
            text = {
                Text(
                    "This removes $orphanedPanelCount panel(s) tagged with a broker that no longer " +
                        "exists - most often left behind by deleting a broker and re-adding it. Panels " +
                        "still tied to a broker you actually have configured are never touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = app.configRepository.pruneOrphanedBrokerData()
                    pruneResultMessage = "Removed $removed panel(s)."
                    showPruneConfirm = false
                }) { Text("Clean Up") }
            },
            dismissButton = {
                TextButton(onClick = { showPruneConfirm = false }) { Text("Cancel") }
            }
        )
    }

    pruneResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { pruneResultMessage = null },
            title = { Text("Done") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { pruneResultMessage = null }) { Text("OK") }
            }
        )
    }
}
