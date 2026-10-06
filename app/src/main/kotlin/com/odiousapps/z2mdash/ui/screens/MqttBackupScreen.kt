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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.BackupCodec
import com.odiousapps.z2mdash.data.restoreConfig
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
                title = { Text(stringResource(R.string.mqtt_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (config.brokers.isEmpty()) {
                Text(stringResource(R.string.mqtt_backup_no_broker))
                return@Column
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == "Backup",
                    onClick = { mode = "Backup"; statusMessage = null },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.mqtt_backup_mode_backup)) }
                SegmentedButton(
                    selected = mode == "Restore",
                    onClick = { mode = "Restore"; statusMessage = null },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.mqtt_backup_mode_restore)) }
            }

            Spacer(Modifier.height(16.dp))
            var brokerExpanded by remember { mutableStateOf(false) }
            val selectedBrokerName = config.brokers.find { it.id == selectedBrokerId }?.name ?: ""
            ExposedDropdownMenuBox(expanded = brokerExpanded, onExpandedChange = { brokerExpanded = it }) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedBrokerName,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.terminal_broker)) },
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
                label = { Text(stringResource(R.string.mqtt_backup_prefix)) },
                placeholder = { Text(DEFAULT_BACKUP_PREFIX) },
                keyboardOptions = tvAwareKeyboardOptions(),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack()
            )

            Spacer(Modifier.height(24.dp))
            if (mode == "Backup") {
                Text(
                    stringResource(R.string.mqtt_backup_explain, prefix),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val fullTopic = BackupCodec.newBackupTopic(prefix)
                        val compressed = BackupCodec.compressToBase64(app.configRepository.exportJson(includeBrokers = false))
                        app.connectionManager.publish(selectedBrokerId, fullTopic, compressed, retain = true)
                        statusMessage = app.resources.getQuantityString(R.plurals.mqtt_backup_published, compressed.length, compressed.length, fullTopic)
                    },
                    enabled = prefix.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.mqtt_backup_publish)) }
            } else {
                Text(
                    stringResource(R.string.mqtt_restore_explain, prefix),
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
                ) { Text(stringResource(R.string.mqtt_restore_scan)) }

                Spacer(Modifier.height(16.dp))
                if (!hasScanned) {
                    Text(
                        stringResource(R.string.mqtt_restore_scan_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (discoveredBackups.isEmpty()) {
                    Text(
                        stringResource(R.string.mqtt_restore_none, prefix),
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
                                        statusMessage = app.getString(R.string.mqtt_restore_not_loaded)
                                        restoringTopic = null
                                    } else {
                                        try {
                                            restoreConfig(app, BackupCodec.decompressFromBase64(raw), preserveBrokers = true)
                                            statusMessage = app.getString(R.string.mqtt_restore_done, displayTime)
                                        } catch (_: Exception) {
                                            statusMessage = app.getString(R.string.mqtt_restore_failed)
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
                                    Text(stringResource(R.string.mqtt_restore_restoring), style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(
                                    enabled = restoringTopic == null,
                                    onClick = { pendingDeleteTopic = backupTopic }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.mqtt_backup_delete_description, displayTime))
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
                    title = { Text(stringResource(R.string.mqtt_backup_delete_title)) },
                    text = { Text(stringResource(R.string.mqtt_backup_delete_text, displayTime)) },
                    confirmButton = {
                        TextButton(onClick = {
                            // Empty retained message is the standard MQTT way to clear a
                            // retained value - our subscription echoes it back, removing it
                            // from latestPayloads (see MqttConnectionManager) and this list.
                            app.connectionManager.publish(selectedBrokerId, topicToDelete, "", retain = true)
                            statusMessage = app.getString(R.string.mqtt_backup_deleted, displayTime)
                            pendingDeleteTopic = null
                        }) { Text(stringResource(R.string.common_delete)) }
                    },
                    dismissButton = { TextButton(onClick = { pendingDeleteTopic = null }) { Text(stringResource(R.string.common_cancel)) } }
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
