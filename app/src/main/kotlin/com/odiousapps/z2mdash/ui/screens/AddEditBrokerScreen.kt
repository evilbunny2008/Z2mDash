package com.odiousapps.z2mdash.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.MqttProtocol
import com.odiousapps.z2mdash.ui.components.CredentialShareDialog
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBrokerScreen(navController: NavController, brokerId: String?) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val context = LocalContext.current
    val config by app.configRepository.config.collectAsState()

    val existing = remember(brokerId, config) { config.brokers.find { it.id == brokerId } }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var broker by remember(existing) {
        mutableStateOf(
            existing ?: Broker(
                id = UUID.randomUUID().toString(),
                name = "My MQTT broker",
                host = ""
            )
        )
    }
    var showAdditional by remember { mutableStateOf(false) }
    var pickerUnavailable by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var protocolExpanded by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    val certPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val bytes = stream.readBytes()
                broker = broker.copy(selfSignedCertBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add Broker" else "Edit Broker") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only for an already-saved broker - sharing an unsaved,
                    // possibly-still-blank draft wouldn't make sense.
                    if (existing != null) {
                        IconButton(onClick = { showShareDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share ${existing.name}'s credentials")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    if (broker.host.isNotBlank()) {
                        app.configRepository.upsertBroker(broker)
                        navController.popBackStack()
                    }
                },
                enabled = broker.host.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Done") }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = broker.name,
                onValueChange = { broker = broker.copy(name = it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = broker.host,
                onValueChange = { broker = broker.copy(host = it) },
                label = { Text("Host") },
                isError = broker.host.isBlank(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = broker.baseTopic,
                onValueChange = { broker = broker.copy(baseTopic = it) },
                label = { Text("Base topic") },
                placeholder = { Text("zigbee2mqtt") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "The app watches \"<base topic>/#\" for devices and their /app configs, " +
                    "instead of every topic on the broker.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))

            ExposedDropdownMenuBox(
                expanded = protocolExpanded,
                onExpandedChange = { protocolExpanded = it }
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = protocolLabel(broker.protocol),
                    onValueChange = {},
                    label = { Text("Protocol") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = protocolExpanded,
                    onDismissRequest = { protocolExpanded = false }
                ) {
                    MqttProtocol.entries.forEach { proto ->
                        DropdownMenuItem(
                            text = { Text(protocolLabel(proto)) },
                            onClick = {
                                broker = broker.copy(protocol = proto, port = defaultPortFor(proto))
                                protocolExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = broker.port.toString(),
                onValueChange = { it.toIntOrNull()?.let { p -> broker = broker.copy(port = p) } },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            if (broker.protocol == MqttProtocol.WS || broker.protocol == MqttProtocol.WSS) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = broker.webSocketPath,
                    onValueChange = { broker = broker.copy(webSocketPath = it) },
                    label = { Text("WebSocket path") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (broker.protocol == MqttProtocol.SSL || broker.protocol == MqttProtocol.WSS) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("This broker uses self-signed SSL/TLS certificate.")
                        Text("Use at your own risk.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = broker.selfSignedCert,
                        onCheckedChange = { broker = broker.copy(selfSignedCert = it) }
                    )
                }
                if (broker.selfSignedCert) {
                    TextButton(onClick = {
                        try {
                            certPickerLauncher.launch("*/*")
                            pickerUnavailable = false
                        } catch (_: ActivityNotFoundException) {
                            pickerUnavailable = true
                        }
                    }) {
                        Text(if (broker.selfSignedCertBase64 == null) "Select certificate file" else "Certificate selected \u2713")
                    }
                    if (pickerUnavailable) {
                        Text(
                            "No file picker app is available on this device.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Authentication", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = broker.authEnabled,
                    onCheckedChange = { broker = broker.copy(authEnabled = it) }
                )
            }
            if (broker.authEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = broker.username,
                    onValueChange = { broker = broker.copy(username = it) },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = broker.password,
                    onValueChange = { broker = broker.copy(password = it) },
                    label = { Text("Password") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showAdditional = !showAdditional },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Additional Parameters", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (showAdditional) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }

            if (showAdditional) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = broker.clientId,
                    onValueChange = { broker = broker.copy(clientId = it) },
                    label = { Text("Client ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Clean Session")
                        Text(
                            "Start a new session on each connection (messages not retained)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = broker.cleanSession, onCheckedChange = { broker = broker.copy(cleanSession = it) })
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = broker.keepAliveSeconds.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> broker = broker.copy(keepAliveSeconds = v) } },
                    label = { Text("Keep Alive Interval") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Time interval in seconds between keep alive messages. Default: 60 seconds. Range: 5\u2013120 seconds",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = broker.connectionTimeoutSeconds.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> broker = broker.copy(connectionTimeoutSeconds = v) } },
                    label = { Text("Connection Timeout") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Maximum wait time for connection. Default: 30 seconds. Range: 1\u2013300 seconds",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto Connect", modifier = Modifier.weight(1f))
                    Switch(checked = broker.autoConnect, onCheckedChange = { broker = broker.copy(autoConnect = it) })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Show Reconnection Status")
                        Text("Show reconnection notifications on main screen", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = broker.showReconnectionStatus,
                        onCheckedChange = { broker = broker.copy(showReconnectionStatus = it) }
                    )
                }
            }

            if (existing != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete broker") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete broker?") },
            text = { Text("This removes \"${existing.name}\" and disconnects from it. Panels linked to it will stop updating.") },
            confirmButton = {
                TextButton(onClick = {
                    app.configRepository.deleteBroker(existing.id)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showShareDialog && existing != null) {
        CredentialShareDialog(broker = existing, onDismiss = { showShareDialog = false })
    }
}

private fun protocolLabel(protocol: MqttProtocol): String = when (protocol) {
    MqttProtocol.TCP -> "TCP - Transmission Control Protocol"
    MqttProtocol.SSL -> "SSL - Secure Sockets Layer"
    MqttProtocol.WS -> "WS - Web Sockets"
    MqttProtocol.WSS -> "WSS - Web Sockets Secure"
}

private fun defaultPortFor(protocol: MqttProtocol): Int = when (protocol) {
    MqttProtocol.TCP -> 1883
    MqttProtocol.SSL -> 8883
    MqttProtocol.WS -> 80
    MqttProtocol.WSS -> 443
}
