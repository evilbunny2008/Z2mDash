package com.odiousapps.z2mdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.ui.components.rememberNotificationPermissionState
import com.odiousapps.z2mdash.ui.tv.toggleableRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()
    val notificationPermission = rememberNotificationPermissionState()
    val anyAlertEnabled = config.smokeAlertsEnabled || config.wateringAlertsEnabled || config.lowBatteryAlertsEnabled

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (anyAlertEnabled && !notificationPermission.granted) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alerts_notifications_off)) },
                    supportingContent = { Text(stringResource(R.string.alerts_notifications_off_detail, stringResource(R.string.app_name))) },
                    leadingContent = {
                        Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { notificationPermission.request() }) { Text(stringResource(R.string.alerts_allow)) }
                            TextButton(onClick = { notificationPermission.openAppNotificationSettings() }) { Text(stringResource(R.string.alerts_open_settings)) }
                        }
                    }
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.alerts_smoke)) },
                supportingContent = { Text(stringResource(R.string.alerts_smoke_detail)) },
                trailingContent = { Switch(checked = config.smokeAlertsEnabled, onCheckedChange = null) },
                modifier = Modifier.toggleableRow(config.smokeAlertsEnabled) { enabled ->
                    if (enabled) notificationPermission.request()
                    app.configRepository.update { it.copy(smokeAlertsEnabled = enabled) }
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.alerts_sound)) },
                supportingContent = { Text(stringResource(R.string.alerts_sound_detail)) },
                trailingContent = {
                    Switch(
                        checked = config.smokeAlertSoundEnabled,
                        enabled = config.smokeAlertsEnabled,
                        onCheckedChange = null
                    )
                },
                modifier = Modifier.toggleableRow(
                    config.smokeAlertSoundEnabled,
                    enabled = config.smokeAlertsEnabled
                ) { enabled ->
                    app.configRepository.update { it.copy(smokeAlertSoundEnabled = enabled) }
                }
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    notificationPermission.request()
                    app.smokeAlertManager.triggerTestAlert()
                }) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.alerts_test))
                }
                Text(
                    stringResource(R.string.alerts_smoke_test_detail),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.alerts_watering)) },
                supportingContent = {
                    Text(stringResource(R.string.alerts_watering_detail))
                },
                trailingContent = { Switch(checked = config.wateringAlertsEnabled, onCheckedChange = null) },
                modifier = Modifier.toggleableRow(config.wateringAlertsEnabled) { enabled ->
                    if (enabled) notificationPermission.request()
                    app.configRepository.update { it.copy(wateringAlertsEnabled = enabled) }
                }
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    notificationPermission.request()
                    app.wateringAlertManager.triggerTestAlert()
                }) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.alerts_test))
                }
                Text(
                    stringResource(R.string.alerts_watering_test_detail),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.alerts_battery)) },
                supportingContent = {
                    Text(stringResource(R.string.alerts_battery_detail))
                },
                trailingContent = { Switch(checked = config.lowBatteryAlertsEnabled, onCheckedChange = null) },
                modifier = Modifier.toggleableRow(config.lowBatteryAlertsEnabled) { enabled ->
                    if (enabled) notificationPermission.request()
                    app.configRepository.update { it.copy(lowBatteryAlertsEnabled = enabled) }
                }
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    notificationPermission.request()
                    app.lowBatteryAlertManager.triggerTestAlert()
                }) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.alerts_test))
                }
                Text(
                    stringResource(R.string.alerts_battery_test_detail),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
