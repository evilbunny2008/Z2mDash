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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import androidx.navigation.NavController
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.forceRepublishAllGroupsAppTopics
import com.odiousapps.z2mdash.ui.components.rememberNotificationPermissionState
import com.odiousapps.z2mdash.ui.tv.LocalIsTv
import com.odiousapps.z2mdash.ui.tv.horizontalSliderDpadFocusNav
import com.odiousapps.z2mdash.ui.tv.toggleableRow

@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as Z2mDashApplication
    val resources = LocalResources.current
    val config by app.configRepository.config.collectAsState()
    val notificationPermission = rememberNotificationPermissionState()
    var showPruneConfirm by remember { mutableStateOf(false) }
    var pruneResultMessage by remember { mutableStateOf<String?>(null) }
    var showForceUploadAllConfirm by remember { mutableStateOf(false) }
    var forceUploadAllResultMessage by remember { mutableStateOf<String?>(null) }
    // Read from the installed package rather than BuildConfig - the latter isn't enabled for
    // this module, and PackageManager is the simplest way to show the actual running version.
    val versionLabel = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        resources.getString(R.string.settings_version, info.versionName, PackageInfoCompat.getLongVersionCode(info))
    }
    // A panel tagged with a brokerId that no longer exists can never update again - usually left
    // behind when a broker is deleted (see ConfigRepository.deleteBroker()'s own comment).
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
                    headlineContent = { Text(stringResource(R.string.brokers_title)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.settings_brokers_count, config.brokers.size, config.brokers.size)) },
                    leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("brokers") }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_background)) },
                    supportingContent = { Text(stringResource(R.string.settings_background_detail)) },
                    leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                    trailingContent = { Switch(checked = config.backgroundWorkEnabled, onCheckedChange = null) },
                    modifier = Modifier.toggleableRow(config.backgroundWorkEnabled) { enabled ->
                        if (enabled) notificationPermission.request()
                        app.configRepository.update { it.copy(backgroundWorkEnabled = enabled) }
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_blink)) },
                    supportingContent = { Text(stringResource(R.string.settings_blink_detail)) },
                    leadingContent = { Icon(Icons.Default.Warning, contentDescription = null) },
                    trailingContent = { Switch(checked = config.staleDataBlinkEnabled, onCheckedChange = null) },
                    modifier = Modifier.toggleableRow(config.staleDataBlinkEnabled) { enabled ->
                        app.configRepository.update { it.copy(staleDataBlinkEnabled = enabled) }
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_tile_width)) },
                    supportingContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_tile_width_detail, config.tileWidthDp))
                            // TVs can go narrower before looking cramped: no touch target to size for,
                            // tileScale (HomeScreen) shrinks font/icon with it, and text reads fine
                            // smaller from a distance - so more, smaller tiles is a net win there.
                            val minTileWidthDp = if (LocalIsTv.current) 40f else 80f
                            Slider(
                                value = config.tileWidthDp.toFloat(),
                                onValueChange = { newValue ->
                                    app.configRepository.update { it.copy(tileWidthDp = newValue.toInt()) }
                                },
                                valueRange = minTileWidthDp..200f,
                                steps = ((200f - minTileWidthDp) / 10f).toInt() - 1, // 10dp increments
                                modifier = Modifier.horizontalSliderDpadFocusNav()
                            )
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Straighten, contentDescription = null) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_undo)) },
                    supportingContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (config.undoToastSeconds == 0) {
                                    stringResource(R.string.settings_undo_detail_off)
                                } else {
                                    stringResource(R.string.settings_undo_detail, config.undoToastSeconds)
                                }
                            )
                            Slider(
                                value = config.undoToastSeconds.toFloat(),
                                onValueChange = { newValue ->
                                    app.configRepository.update { it.copy(undoToastSeconds = newValue.toInt()) }
                                },
                                valueRange = 0f..30f,
                                steps = 29, // 1s increments, 0 (off) through 30
                                modifier = Modifier.horizontalSliderDpadFocusNav()
                            )
                        }
                    },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alerts_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_alerts_detail)) },
                    leadingContent = { Icon(Icons.Default.Warning, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("alertSettings") }
                )
            }
            if (orphanedPanelCount > 0) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_orphans)) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.settings_orphans_detail, orphanedPanelCount, orphanedPanelCount))
                        },
                        leadingContent = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                        modifier = Modifier.clickable { showPruneConfirm = true }
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_backup)) },
                    supportingContent = { Text(stringResource(R.string.settings_backup_detail)) },
                    leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("backupRestore") }
                )
            }
            if (config.groups.isNotEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_force_upload_all)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_force_upload_all_detail))
                        },
                        leadingContent = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                        modifier = Modifier.clickable { showForceUploadAllConfirm = true }
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_about)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.settings_about_tagline, stringResource(R.string.app_name)))
                            Text(versionLabel)
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
            }
        }
    }

    if (showPruneConfirm) {
        AlertDialog(
            onDismissRequest = { showPruneConfirm = false },
            title = { Text(stringResource(R.string.settings_orphans_confirm_title)) },
            text = {
                Text(pluralStringResource(R.plurals.settings_orphans_confirm, orphanedPanelCount, orphanedPanelCount))
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = app.configRepository.pruneOrphanedBrokerData()
                    pruneResultMessage = resources.getQuantityString(R.plurals.settings_orphans_removed, removed, removed)
                    showPruneConfirm = false
                }) { Text(stringResource(R.string.settings_orphans_clean)) }
            },
            dismissButton = {
                TextButton(onClick = { showPruneConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pruneResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { pruneResultMessage = null },
            title = { Text(stringResource(R.string.common_done)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { pruneResultMessage = null }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }

    if (showForceUploadAllConfirm) {
        AlertDialog(
            onDismissRequest = { showForceUploadAllConfirm = false },
            title = { Text(stringResource(R.string.settings_force_upload_all_confirm_title)) },
            text = {
                Text(stringResource(R.string.settings_force_upload_all_confirm))
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = forceRepublishAllGroupsAppTopics(app)
                    forceUploadAllResultMessage = resources.getQuantityString(R.plurals.settings_force_upload_all_done, count, count)
                    showForceUploadAllConfirm = false
                }) { Text(stringResource(R.string.force_upload)) }
            },
            dismissButton = {
                TextButton(onClick = { showForceUploadAllConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    forceUploadAllResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { forceUploadAllResultMessage = null },
            title = { Text(stringResource(R.string.common_done)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { forceUploadAllResultMessage = null }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }
}
