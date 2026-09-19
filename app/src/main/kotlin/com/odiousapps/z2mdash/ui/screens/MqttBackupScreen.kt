package com.odiousapps.z2mdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import com.odiousapps.z2mdash.ui.tv.tvAwareKeyboardOptions

private const val DEFAULT_BACKUP_PREFIX = "z2mdash/backup"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MqttBackupScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()
    val payloads by app.connectionManager.latestPayloads.collectAsState()

    var mode by remember { mutableStateOf("Backup") }
    var selectedBrokerId by remember { mutableStateOf(config.brokers.firstOrNull()?.id ?: "") }
    var topicPrefix by remember { mutableStateOf(DEFAULT_BACKUP_PREFIX) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var hasScanned by remember { mutableStateOf(false) }
    var restoringTopic by remember { mutableStateOf<String?>(null) }
    var pendingDeleteTopic by remember { mutableStateOf<String?>(null) }

    // Backup topics under "<prefix>/", newest first - recomputed off the live
    // payloads map so the list updates as retained backups arrive after a scan.
    val prefix = topicPrefix.trim().trim('/')
    val discoveredBackups = remember(payloads, selectedBrokerId, prefix) {
        val keyPrefix = "$selectedBrokerId|$prefix/"
        payloads.keys
            .filter { it.startsWith(keyPrefix) }
            .map { it.removePrefix("$selectedBrokerId|") }
            .sortedDescending()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Backup / Restore via MQTT") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (config.brokers.isEmpty()) {
                Text("Add a broker first (Settings \u2192 Brokers) before using this.")
                return@Column
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == "Backup",
                    onClick = { mode = "Backup"; statusMessage = null },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Backup") }
                SegmentedButton(
                    selected = mode == "Restore",
                    onClick = { mode = "Restore"; statusMessage = null },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Restore") }
            }

            Spacer(Modifier.height(16.dp))
            var brokerExpanded by remember { mutableStateOf(false) }
            val selectedBrokerName = config.brokers.find { it.id == selectedBrokerId }?.name ?: ""
            ExposedDropdownMenuBox(expanded = brokerExpanded, onExpandedChange = { brokerExpanded = it }) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedBrokerName,
                    onValueChange = {},
                    label = { Text("Broker") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = brokerExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = brokerExpanded, onDismissRequest = { brokerExpanded = false }) {
                    config.brokers.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name) }, onClick = {
                            selectedBrokerId = b.id
                            brokerExpanded = false
                            hasScanned = false
                        })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = topicPrefix,
                onValueChange = { topicPrefix = it; hasScanned = false },
                label = { Text("Backup topic prefix") },
                placeholder = { Text(DEFAULT_BACKUP_PREFIX) },
                keyboardOptions = tvAwareKeyboardOptions(),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack()
            )

            Spacer(Modifier.height(24.dp))
            if (mode == "Backup") {
                Text(
                    "Publishes your groups and panels (not broker host/username/password - those " +
                        "stay on this device) - gzip compressed, then base64-encoded so it travels as " +
                        "a normal MQTT payload - as a new retained message under \"$prefix/<timestamp>\", " +
                        "keeping every previous backup intact.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val fullTopic = BackupCodec.newBackupTopic(prefix)
                        val compressed = BackupCodec.compressToBase64(app.configRepository.exportJson(includeBrokers = false))
                        app.connectionManager.publish(selectedBrokerId, fullTopic, compressed, retain = true)
                        statusMessage = "Published (${compressed.length} bytes) to $fullTopic"
                    },
                    enabled = prefix.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Publish Backup") }
            } else {
                Text(
                    "Scans \"$prefix/#\" for every backup that broker has retained, newest first. " +
                        "Restoring replaces your groups and panels, but keeps your current brokers as-is " +
                        "(they weren't included in the backup) - so this only reconnects cleanly on the " +
                        "same broker setup the backup was taken from.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        app.connectionManager.subscribe(selectedBrokerId, "$prefix/#")
                        hasScanned = true
                        statusMessage = null
                    },
                    enabled = prefix.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan for Backups") }

                Spacer(Modifier.height(16.dp))
                if (!hasScanned) {
                    Text(
                        "Tap \"Scan for Backups\" to look for retained backups on this broker.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (discoveredBackups.isEmpty()) {
                    Text(
                        "No backups found yet under \"$prefix/\" - retained messages can take a " +
                            "moment to arrive after scanning, or there may not be any published there.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    discoveredBackups.forEach { backupTopic ->
                        val displayTime = BackupCodec.displayTimestamp(backupTopic) ?: backupTopic
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = restoringTopic == null) {
                                    restoringTopic = backupTopic
                                    val raw = payloads["$selectedBrokerId|$backupTopic"]
                                    if (raw == null) {
                                        statusMessage = "That backup's data isn't loaded yet - try scanning again."
                                        restoringTopic = null
                                    } else {
                                        try {
                                            app.configRepository.importJsonPreservingBrokers(BackupCodec.decompressFromBase64(raw))
                                            statusMessage = "Restored from $displayTime"
                                        } catch (_: Exception) {
                                            statusMessage = "Restore failed: that topic's payload wasn't a valid backup"
                                        } finally {
                                            restoringTopic = null
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(displayTime, modifier = Modifier.weight(1f))
                                if (restoringTopic == backupTopic) {
                                    Text("Restoring\u2026", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(
                                    enabled = restoringTopic == null,
                                    onClick = { pendingDeleteTopic = backupTopic }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete backup from $displayTime")
                                }
                            }
                        }
                    }
                }
            }

            pendingDeleteTopic?.let { topicToDelete ->
                val displayTime = BackupCodec.displayTimestamp(topicToDelete) ?: topicToDelete
                AlertDialog(
                    onDismissRequest = { pendingDeleteTopic = null },
                    title = { Text("Delete this backup?") },
                    text = { Text("Removes the retained backup from $displayTime. This can't be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            // Empty retained message is the standard MQTT way to clear a
                            // retained value - our subscription echoes it back, removing it
                            // from latestPayloads (see MqttConnectionManager) and this list.
                            app.connectionManager.publish(selectedBrokerId, topicToDelete, "", retain = true)
                            statusMessage = "Deleted backup from $displayTime"
                            pendingDeleteTopic = null
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { pendingDeleteTopic = null }) { Text("Cancel") } }
                )
            }

            statusMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
