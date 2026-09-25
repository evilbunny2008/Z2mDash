package com.odiousapps.z2mdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.ui.tv.toggleableRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Alarm/Alert") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text("Smoke Alerts") },
                supportingContent = { Text("Notify if any device reports smoke detected") },
                trailingContent = { Switch(checked = config.smokeAlertsEnabled, onCheckedChange = null) },
                modifier = Modifier.toggleableRow(config.smokeAlertsEnabled) { enabled ->
                    app.configRepository.update { it.copy(smokeAlertsEnabled = enabled) }
                }
            )
            ListItem(
                headlineContent = { Text("Alert Sound") },
                supportingContent = { Text("Play a loud alarm-style sound alongside the notification") },
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
                Button(onClick = { app.smokeAlertManager.triggerTestAlert() }) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Test Notification")
                }
                Text(
                    "Fires a real notification (with sound, if enabled above) using the exact " +
                        "same logic as a genuine smoke alert \u2013 a good way to confirm it'll " +
                        "actually get your attention before you need it to.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            ListItem(
                headlineContent = { Text("Watering Alerts") },
                supportingContent = {
                    Text("Vibrate and chime when a moisture sensor rises through the middle of its ideal range")
                },
                trailingContent = { Switch(checked = config.wateringAlertsEnabled, onCheckedChange = null) },
                modifier = Modifier.toggleableRow(config.wateringAlertsEnabled) { enabled ->
                    app.configRepository.update { it.copy(wateringAlertsEnabled = enabled) }
                }
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { app.wateringAlertManager.triggerTestAlert() }) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Test Notification")
                }
                Text(
                    "Fires a real notification with the watering chime and vibration, regardless " +
                        "of the toggle above \u2013 a good way to hear it before you rely on it.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
