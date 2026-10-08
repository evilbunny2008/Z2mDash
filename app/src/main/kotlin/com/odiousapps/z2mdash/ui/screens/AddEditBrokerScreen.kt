package com.odiousapps.z2mdash.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.MqttProtocol
import com.odiousapps.z2mdash.ui.components.CredentialImportDialog
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import com.odiousapps.z2mdash.ui.tv.onDpadSelect
import com.odiousapps.z2mdash.ui.tv.rememberTvKeyboardGate
import com.odiousapps.z2mdash.ui.tv.toggleableRow
import com.odiousapps.z2mdash.ui.tv.tvAwareKeyboardOptions
import com.odiousapps.z2mdash.ui.tv.tvKeyboardGate
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBrokerScreen(navController: NavController, brokerId: String?) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val context = LocalContext.current
    val resources = LocalResources.current
    val config by app.configRepository.config.collectAsState()

    val existing = remember(brokerId, config) { config.brokers.find { it.id == brokerId } }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Keyed on brokerId, not "existing" itself, so a config change elsewhere (including this
    // screen's own updatePermitJoinDevice() call) doesn't reset unsaved edits back to disk state.
    var broker by remember(brokerId) {
        mutableStateOf(
            existing ?: Broker(
                id = UUID.randomUUID().toString(),
                name = resources.getString(R.string.broker_default_name),
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
                title = { Text(stringResource(if (existing == null) R.string.broker_add_title else R.string.broker_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    if (broker.host.isNotBlank()) {
                        app.configRepository.upsertBroker(broker)
                        // If reached via WelcomeScreen's "Add a Broker", pop Welcome too - it has
                        // no logic to skip itself now that a broker exists, so a normal single
                        // pop would strand the user there needing a second back-tap.
                        if (navController.previousBackStackEntry?.destination?.route == "welcome") {
                            navController.popBackStack("welcome", inclusive = true)
                        } else {
                            navController.popBackStack()
                        }
                    }
                },
                enabled = broker.host.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text(stringResource(R.string.common_done)) }
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
                ) { Text(stringResource(R.string.broker_import_code)) }
                Spacer(Modifier.height(16.dp))
            }
            val nameKeyboardGate = rememberTvKeyboardGate()
            OutlinedTextField(
                value = broker.name,
                onValueChange = { broker = broker.copy(name = it) },
                label = { Text(stringResource(R.string.broker_name)) },
                readOnly = nameKeyboardGate.readOnly,
                keyboardOptions = tvAwareKeyboardOptions(),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(nameKeyboardGate)
            )
            Spacer(Modifier.height(16.dp))
            val hostKeyboardGate = rememberTvKeyboardGate()
            OutlinedTextField(
                value = broker.host,
                onValueChange = { broker = broker.copy(host = it) },
                label = { Text(stringResource(R.string.broker_host)) },
                isError = broker.host.isBlank(),
                readOnly = hostKeyboardGate.readOnly,
                keyboardOptions = tvAwareKeyboardOptions(),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(hostKeyboardGate)
            )
            Spacer(Modifier.height(16.dp))

            val baseTopicKeyboardGate = rememberTvKeyboardGate()
            OutlinedTextField(
                value = broker.baseTopic,
                onValueChange = { broker = broker.copy(baseTopic = it) },
                label = { Text(stringResource(R.string.broker_base_topic)) },
                placeholder = { Text("zigbee2mqtt") },
                readOnly = baseTopicKeyboardGate.readOnly,
                keyboardOptions = tvAwareKeyboardOptions(),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(baseTopicKeyboardGate)
            )
            Text(
                stringResource(R.string.broker_base_topic_help),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))

            // Lets Down (see onDirectionDown below) jump into the open menu - without an explicit
            // focus target, nothing in the popup ever received D-pad focus (same gap as the
            // "Permit join via" dropdown further down this screen).
            val firstProtocolFocusRequester = remember { FocusRequester() }
            ExposedDropdownMenuBox(
                expanded = protocolExpanded,
                onExpandedChange = { protocolExpanded = it }
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = stringResource(protocolLabel(broker.protocol)),
                    onValueChange = {},
                    label = { Text(stringResource(R.string.broker_protocol)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                    modifier = Modifier.fillMaxWidth()
                        .clearFocusOnBack(
                            onDirectionDown = {
                                if (protocolExpanded) {
                                    firstProtocolFocusRequester.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            }
                        )
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = protocolExpanded,
                    onDismissRequest = { protocolExpanded = false }
                ) {
                    MqttProtocol.entries.forEachIndexed { index, proto ->
                        val onProtoClick = {
                            broker = broker.copy(protocol = proto, port = defaultPortFor(proto))
                            protocolExpanded = false
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(protocolLabel(proto))) },
                            onClick = onProtoClick,
                            modifier = Modifier
                                .onDpadSelect(onProtoClick)
                                .then(
                                    if (index == 0) {
                                        Modifier.focusRequester(firstProtocolFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            val portKeyboardGate = rememberTvKeyboardGate()
            OutlinedTextField(
                value = broker.port.toString(),
                onValueChange = { it.toIntOrNull()?.let { p -> broker = broker.copy(port = p) } },
                label = { Text(stringResource(R.string.broker_port)) },
                readOnly = portKeyboardGate.readOnly,
                keyboardOptions = tvAwareKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number)),
                modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(portKeyboardGate)
            )

            if (broker.protocol == MqttProtocol.WS || broker.protocol == MqttProtocol.WSS) {
                Spacer(Modifier.height(16.dp))
                val webSocketPathKeyboardGate = rememberTvKeyboardGate()
                OutlinedTextField(
                    value = broker.webSocketPath,
                    onValueChange = { broker = broker.copy(webSocketPath = it) },
                    label = { Text(stringResource(R.string.broker_ws_path)) },
                    readOnly = webSocketPathKeyboardGate.readOnly,
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(webSocketPathKeyboardGate)
                )
            }

            if (broker.protocol == MqttProtocol.SSL || broker.protocol == MqttProtocol.WSS) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .toggleableRow(broker.selfSignedCert) { broker = broker.copy(selfSignedCert = it) }
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.broker_self_signed))
                        Text(stringResource(R.string.broker_self_signed_risk), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = broker.selfSignedCert, onCheckedChange = null)
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
                        Text(stringResource(if (broker.selfSignedCertBase64 == null) R.string.broker_select_cert else R.string.broker_cert_selected))
                    }
                    if (pickerUnavailable) {
                        Text(
                            stringResource(R.string.no_file_picker),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .toggleableRow(broker.authEnabled) { broker = broker.copy(authEnabled = it) }
            ) {
                Text(stringResource(R.string.broker_auth), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = broker.authEnabled, onCheckedChange = null)
            }
            if (broker.authEnabled) {
                Spacer(Modifier.height(8.dp))
                val usernameKeyboardGate = rememberTvKeyboardGate()
                OutlinedTextField(
                    value = broker.username,
                    onValueChange = { broker = broker.copy(username = it) },
                    label = { Text(stringResource(R.string.broker_username)) },
                    readOnly = usernameKeyboardGate.readOnly,
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(usernameKeyboardGate)
                )
                Spacer(Modifier.height(8.dp))
                val passwordKeyboardGate = rememberTvKeyboardGate()
                OutlinedTextField(
                    value = broker.password,
                    onValueChange = { broker = broker.copy(password = it) },
                    label = { Text(stringResource(R.string.backup_password)) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(stringResource(if (showPassword) R.string.broker_hide else R.string.broker_show))
                        }
                    },
                    readOnly = passwordKeyboardGate.readOnly,
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(passwordKeyboardGate)
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showAdditional = !showAdditional },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.broker_additional), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (showAdditional) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }

            if (showAdditional) {
                Spacer(Modifier.height(8.dp))
                val clientIdKeyboardGate = rememberTvKeyboardGate()
                OutlinedTextField(
                    value = broker.clientId,
                    onValueChange = { broker = broker.copy(clientId = it) },
                    label = { Text(stringResource(R.string.broker_client_id)) },
                    readOnly = clientIdKeyboardGate.readOnly,
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(clientIdKeyboardGate)
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .toggleableRow(broker.cleanSession) { broker = broker.copy(cleanSession = it) }
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.broker_clean_session))
                        Text(
                            stringResource(R.string.broker_clean_session_detail),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = broker.cleanSession, onCheckedChange = null)
                }
                Spacer(Modifier.height(16.dp))
                val keepAliveKeyboardGate = rememberTvKeyboardGate()
                OutlinedTextField(
                    value = broker.keepAliveSeconds.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> broker = broker.copy(keepAliveSeconds = v) } },
                    label = { Text(stringResource(R.string.broker_keep_alive)) },
                    readOnly = keepAliveKeyboardGate.readOnly,
                    keyboardOptions = tvAwareKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number)),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(keepAliveKeyboardGate)
                )
                Text(
                    stringResource(R.string.broker_keep_alive_help),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                val connectionTimeoutKeyboardGate = rememberTvKeyboardGate()
                OutlinedTextField(
                    value = broker.connectionTimeoutSeconds.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> broker = broker.copy(connectionTimeoutSeconds = v) } },
                    label = { Text(stringResource(R.string.broker_timeout)) },
                    readOnly = connectionTimeoutKeyboardGate.readOnly,
                    keyboardOptions = tvAwareKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number)),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack().tvKeyboardGate(connectionTimeoutKeyboardGate)
                )
                Text(
                    stringResource(R.string.broker_timeout_help),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .toggleableRow(broker.autoConnect) { broker = broker.copy(autoConnect = it) }
                ) {
                    Text(stringResource(R.string.broker_auto_connect), modifier = Modifier.weight(1f))
                    Switch(checked = broker.autoConnect, onCheckedChange = null)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .toggleableRow(broker.showReconnectionStatus) { broker = broker.copy(showReconnectionStatus = it) }
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.broker_show_reconnect))
                        Text(stringResource(R.string.broker_show_reconnect_detail), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = broker.showReconnectionStatus, onCheckedChange = null)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .toggleableRow(broker.autoAcceptDiscoveredDevices) { broker = broker.copy(autoAcceptDiscoveredDevices = it) }
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.broker_auto_accept))
                        Text(
                            stringResource(R.string.broker_auto_accept_detail),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = broker.autoAcceptDiscoveredDevices, onCheckedChange = null)
                }
            }

            if (existing != null) {
                // Permit Join (including picking a base topic and a specific router to extend
                // joining through) now lives entirely in a dialog on the Home screen, reachable
                // for every broker/topic at once without navigating here - see HomeScreen's own
                // Permit Join bar/dialog.
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.broker_delete)) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.broker_delete_title)) },
            text = { Text(stringResource(R.string.broker_delete_text, existing.name)) },
            confirmButton = {
                TextButton(onClick = {
                    app.configRepository.deleteBroker(existing.id)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
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

@StringRes
private fun protocolLabel(protocol: MqttProtocol): Int = when (protocol) {
    MqttProtocol.TCP -> R.string.protocol_tcp
    MqttProtocol.SSL -> R.string.protocol_ssl
    MqttProtocol.WS -> R.string.protocol_ws
    MqttProtocol.WSS -> R.string.protocol_wss
}

/**
 * Applies a credential import's fields onto [current]. Every field but "Hostname" is optional,
 * falling back to [current] when missing/unparsable. Field names (PascalCase) match the
 * "Key: Value" preset format from sync.odiousapps.com/manage_credentials.php.
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
 * Maps a credential import's "Protocol" field (standard MQTT scheme names: MQTT/MQTTS/WS/WSS)
 * onto this app's transport-named MqttProtocol enum (TCP/SSL/WS/WSS). Null when unrecognised,
 * so the caller falls back to the existing value instead of resetting it.
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
