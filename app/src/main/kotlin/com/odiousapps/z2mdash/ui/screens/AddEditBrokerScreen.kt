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
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.MqttProtocol
import com.odiousapps.z2mdash.ui.components.CredentialImportDialog
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBrokerScreen(navController: NavController, brokerId: String?) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val context = LocalContext.current
    val config by app.configRepository.config.collectAsState()
    val latestPayloads by app.connectionManager.latestPayloads.collectAsState()

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
    var showImportDialog by remember { mutableStateOf(false) }

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
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    if (broker.host.isNotBlank()) {
                        app.configRepository.upsertBroker(broker)
                        // Reached via WelcomeScreen's "Add a Broker" (shown only
                        // when there were no brokers at all) - a plain single-
                        // level pop would land back on Welcome, which has no
                        // logic of its own to skip itself now that a broker
                        // exists, leaving the user to tap back a second time
                        // before Home actually appeared. Popping welcome off
                        // too goes straight there instead. Any other entry
                        // point (e.g. the Brokers list) still gets a normal
                        // single-level pop, back to wherever it came from.
                        if (navController.previousBackStackEntry?.destination?.route == "welcome") {
                            navController.popBackStack("welcome", inclusive = true)
                        } else {
                            navController.popBackStack()
                        }
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
            if (existing == null) {
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import via code") }
                Spacer(Modifier.height(16.dp))
            }
            OutlinedTextField(
                value = broker.name,
                onValueChange = { broker = broker.copy(name = it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().clearFocusOnBack()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = broker.host,
                onValueChange = { broker = broker.copy(host = it) },
                label = { Text("Host") },
                isError = broker.host.isBlank(),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = broker.baseTopic,
                onValueChange = { broker = broker.copy(baseTopic = it) },
                label = { Text("Base topic") },
                placeholder = { Text("zigbee2mqtt") },
                modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                modifier = Modifier.fillMaxWidth().clearFocusOnBack()
            )

            if (broker.protocol == MqttProtocol.WS || broker.protocol == MqttProtocol.WSS) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = broker.webSocketPath,
                    onValueChange = { broker = broker.copy(webSocketPath = it) },
                    label = { Text("WebSocket path") },
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-Accept Discovered Devices")
                        Text(
                            "Add a newly-seen device's group/clusters/panels immediately, instead of prompting to accept each one",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = broker.autoAcceptDiscoveredDevices,
                        onCheckedChange = { broker = broker.copy(autoAcceptDiscoveredDevices = it) }
                    )
                }
            }

            if (existing != null) {
                // "<baseTopic>/#" is already subscribed continuously for every
                // configured broker (see MqttConnectionManager.applyConfig), so
                // bridge/info's retained/live payload is already flowing into
                // latestPayloads without needing a subscription of its own here.
                val baseTopicNormalized = remember(broker.baseTopic) { PermitJoin.normalizedBaseTopic(broker.baseTopic) }
                var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1_000)
                        nowMillis = System.currentTimeMillis()
                    }
                }
                val permitJoinStatus = remember(latestPayloads, existing.id, baseTopicNormalized, nowMillis) {
                    PermitJoin.status(latestPayloads, existing.id, baseTopicNormalized, nowMillis)
                }
                // Zigbee2MQTT also retains its whole device list on "<baseTopic>/bridge/devices" -
                // already flowing into latestPayloads for the same reason permit_join's own
                // response is. Only routers/the coordinator can actually be targeted by permit_join's
                // "device" field (end devices don't route child joins), so end devices are filtered
                // out of the suggestion list rather than just listing every known device.
                val routerFriendlyNames = remember(latestPayloads, existing.id, baseTopicNormalized) {
                    val devicesPayload = latestPayloads["${existing.id}|$baseTopicNormalized/bridge/devices"]
                    devicesPayload?.let { raw ->
                        try {
                            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                                val obj = element as? JsonObject ?: return@mapNotNull null
                                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                                if (type == "Router" || type == "Coordinator") {
                                    obj["friendly_name"]?.jsonPrimitive?.contentOrNull
                                } else null
                            }.sorted()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } ?: emptyList()
                }

                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Permit Join", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (permitJoinStatus.isOn) {
                                "Open for ${PermitJoin.formatRemaining(permitJoinStatus.remainingSeconds)} more"
                            } else {
                                "Allow new Zigbee devices to join this network for a few minutes"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = permitJoinStatus.isOn,
                        onCheckedChange = { enabled ->
                            val payload = PermitJoin.requestPayload(broker.permitJoinDevice, if (enabled) 254 else 0)
                            app.connectionManager.publish(existing.id, PermitJoin.requestTopic(baseTopicNormalized), payload)
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                var permitJoinDeviceExpanded by remember { mutableStateOf(false) }
                val filteredRouterNames = remember(routerFriendlyNames, broker.permitJoinDevice) {
                    routerFriendlyNames.filter { it.contains(broker.permitJoinDevice, ignoreCase = true) }
                }
                ExposedDropdownMenuBox(
                    expanded = permitJoinDeviceExpanded && filteredRouterNames.isNotEmpty(),
                    onExpandedChange = { permitJoinDeviceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = broker.permitJoinDevice,
                        onValueChange = {
                            broker = broker.copy(permitJoinDevice = it)
                            permitJoinDeviceExpanded = true
                        },
                        label = { Text("Permit join via (optional)") },
                        placeholder = { Text("Blank = whole network") },
                        trailingIcon = if (routerFriendlyNames.isNotEmpty()) {
                            { ExposedDropdownMenuDefaults.TrailingIcon(expanded = permitJoinDeviceExpanded) }
                        } else null,
                        modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = permitJoinDeviceExpanded && filteredRouterNames.isNotEmpty(),
                        onDismissRequest = { permitJoinDeviceExpanded = false }
                    ) {
                        filteredRouterNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    broker = broker.copy(permitJoinDevice = name)
                                    permitJoinDeviceExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "Friendly name of a specific router to extend joining through, or \"Coordinator\" for " +
                        "just the coordinator. Leave blank to permit joining via every router and the " +
                        "coordinator at once.",
                    style = MaterialTheme.typography.bodySmall
                )

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

    if (showImportDialog) {
        CredentialImportDialog(
            onImported = { fields ->
                broker = applyImportedFields(broker, fields)
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false }
        )
    }
}

private fun protocolLabel(protocol: MqttProtocol): String = when (protocol) {
    MqttProtocol.TCP -> "TCP - Transmission Control Protocol"
    MqttProtocol.SSL -> "SSL - Secure Sockets Layer"
    MqttProtocol.WS -> "WS - Web Sockets"
    MqttProtocol.WSS -> "WSS - Web Sockets Secure"
}

/**
 * Applies a credential import's fields onto [current] - every field
 * beyond "Hostname" itself (already guaranteed present by
 * CredentialImportDialog before this is ever called) is optional and
 * falls back to whatever [current] already had when missing or
 * unparsable, so a preset only needs to specify the settings that
 * actually differ from this app's own defaults rather than all of them.
 * Field names mirror the Broker properties they map to one-for-one
 * (PascalCase, since that's how they're typed as plain "Key: Value"
 * lines on mx3launcher.odiousapps.com/manage_credentials.php) - keep
 * this in sync with that page's own hint text and the website's README
 * if any of these names or the Broker shape itself ever changes.
 */
private fun applyImportedFields(current: Broker, fields: Map<String, String>): Broker {
    val username = fields["Username"]
    val password = fields["Password"]
    return current.copy(
        name = importedString(fields["Name"]) ?: current.name,
        host = fields["Hostname"] ?: current.host,
        protocol = importedProtocol(fields["Protocol"]) ?: current.protocol,
        port = importedInt(fields["Port"]) ?: current.port,
        authEnabled = importedBoolean(fields["AuthEnabled"])
            ?: (!username.isNullOrBlank() || !password.isNullOrBlank() || current.authEnabled),
        username = importedString(username) ?: current.username,
        password = importedString(password) ?: current.password,
        selfSignedCert = importedBoolean(fields["SelfSignedCert"]) ?: current.selfSignedCert,
        selfSignedCertBase64 = importedString(fields["SelfSignedCertBase64"]) ?: current.selfSignedCertBase64,
        webSocketPath = importedString(fields["WebSocketPath"]) ?: current.webSocketPath,
        clientId = importedString(fields["ClientId"]) ?: current.clientId,
        cleanSession = importedBoolean(fields["CleanSession"]) ?: current.cleanSession,
        keepAliveSeconds = importedInt(fields["KeepAliveSeconds"]) ?: current.keepAliveSeconds,
        connectionTimeoutSeconds = importedInt(fields["ConnectionTimeoutSeconds"]) ?: current.connectionTimeoutSeconds,
        autoConnect = importedBoolean(fields["AutoConnect"]) ?: current.autoConnect,
        showReconnectionStatus = importedBoolean(fields["ShowReconnectionStatus"]) ?: current.showReconnectionStatus,
        baseTopic = importedString(fields["BaseTopic"]) ?: current.baseTopic,
        autoAcceptDiscoveredDevices = importedBoolean(fields["AutoAccept"]) ?: current.autoAcceptDiscoveredDevices
    )
}

/** Null for a missing/blank field, so the caller can fall back to the existing value. */
private fun importedString(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/** Null for a missing/unparsable field, so the caller can fall back to the existing value. */
private fun importedInt(value: String?): Int? = value?.trim()?.toIntOrNull()

/**
 * Maps a credential import's "Protocol" field (the standard MQTT scheme
 * names - MQTT/MQTTS/WS/WSS, matching how brokers themselves are
 * usually documented) onto this app's own MqttProtocol enum (named
 * after the underlying transport instead - TCP/SSL/WS/WSS). Null for a
 * missing or unrecognised value, so the caller can fall back to
 * whatever the broker draft already had rather than silently resetting it.
 */
private fun importedProtocol(value: String?): MqttProtocol? = when (value?.trim()?.uppercase()) {
    "MQTT" -> MqttProtocol.TCP
    "MQTTS" -> MqttProtocol.SSL
    "WS" -> MqttProtocol.WS
    "WSS" -> MqttProtocol.WSS
    else -> null
}

/** Null for a missing/unrecognised boolean field, so the caller can fall back to the existing value. */
private fun importedBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> null
}

private fun defaultPortFor(protocol: MqttProtocol): Int = when (protocol) {
    MqttProtocol.TCP -> 1883
    MqttProtocol.SSL -> 8883
    MqttProtocol.WS -> 80
    MqttProtocol.WSS -> 443
}
