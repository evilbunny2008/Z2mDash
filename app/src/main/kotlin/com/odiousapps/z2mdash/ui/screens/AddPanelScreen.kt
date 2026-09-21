package com.odiousapps.z2mdash.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.TileIcon
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import com.odiousapps.z2mdash.ui.tv.tvAwareKeyboardOptions
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPanelScreen(navController: NavController, groupId: String, panelId: String? = null) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()

    val existing = remember(groupId, panelId, config) {
        panelId?.let { id -> config.groups.find { it.id == groupId }?.panels?.find { it.id == id } }
    }
    val isEditing = existing != null
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var panelType by remember(existing) {
        mutableStateOf(
            when (existing) {
                is Panel.Toggle -> "Toggle"
                is Panel.Button -> "Button"
                else -> "Sensor"
            }
        )
    }
    var selectedBrokerId by remember(existing) {
        mutableStateOf(existing?.brokerId ?: config.brokers.firstOrNull()?.id ?: "")
    }
    var label by remember(existing) { mutableStateOf(existing?.label ?: "") }
    var clusterName by remember(existing) { mutableStateOf(existing?.clusterName ?: "") }
    var displayOrderText by remember(existing) {
        mutableStateOf(existing?.displayOrder?.takeIf { it != Int.MAX_VALUE }?.toString() ?: "")
    }
    var icon by remember(existing) {
        mutableStateOf(
            when (existing) {
                is Panel.Sensor -> existing.icon
                is Panel.Toggle -> existing.icon
                is Panel.Button -> existing.icon
                else -> TileIcon.GAUGE
            }
        )
    }

    // Sensor fields
    var topic by remember(existing) { mutableStateOf((existing as? Panel.Sensor)?.topic ?: "") }
    var jsonPath by remember(existing) { mutableStateOf((existing as? Panel.Sensor)?.jsonPath ?: "") }
    var unit by remember(existing) { mutableStateOf((existing as? Panel.Sensor)?.unit ?: "") }
    var decimalsText by remember(existing) {
        mutableStateOf(((existing as? Panel.Sensor)?.decimals ?: 0).toString())
    }
    var idealRangeTopic by remember(existing) {
        mutableStateOf((existing as? Panel.Sensor)?.idealRangeTopic ?: "")
    }
    var idealMinPath by remember(existing) {
        mutableStateOf((existing as? Panel.Sensor)?.idealMinPath ?: "min")
    }
    var idealMaxPath by remember(existing) {
        mutableStateOf((existing as? Panel.Sensor)?.idealMaxPath ?: "max")
    }

    // Toggle fields
    var commandTopic by remember(existing) {
        mutableStateOf((existing as? Panel.Toggle)?.commandTopic ?: (existing as? Panel.Button)?.commandTopic ?: "")
    }
    var onPayload by remember(existing) { mutableStateOf((existing as? Panel.Toggle)?.onPayload ?: "ON") }
    var offPayload by remember(existing) { mutableStateOf((existing as? Panel.Toggle)?.offPayload ?: "OFF") }
    var stateTopic by remember(existing) { mutableStateOf((existing as? Panel.Toggle)?.stateTopic ?: "") }
    var stateJsonPath by remember(existing) {
        mutableStateOf((existing as? Panel.Toggle)?.stateJsonPath ?: "")
    }

    // Button field
    var buttonPayload by remember(existing) { mutableStateOf((existing as? Panel.Button)?.payload ?: "") }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Panel" else "Add Panel") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (config.brokers.isNotEmpty()) {
                Button(
                    onClick = {
                        val displayOrderValue = displayOrderText.toIntOrNull() ?: Int.MAX_VALUE
                        val panel: Panel = when (panelType) {
                            "Sensor" -> Panel.Sensor(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                label = label.ifBlank { "Sensor" },
                                brokerId = selectedBrokerId,
                                topic = topic,
                                jsonPath = jsonPath,
                                unit = unit,
                                icon = icon,
                                idealRangeTopic = idealRangeTopic,
                                idealMinPath = idealMinPath,
                                idealMaxPath = idealMaxPath,
                                clusterName = clusterName,
                                displayOrder = displayOrderValue,
                                decimals = decimalsText.toIntOrNull() ?: 0
                            )
                            "Toggle" -> Panel.Toggle(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                label = label.ifBlank { "Toggle" },
                                brokerId = selectedBrokerId,
                                commandTopic = commandTopic,
                                onPayload = onPayload,
                                offPayload = offPayload,
                                stateTopic = stateTopic,
                                stateJsonPath = stateJsonPath,
                                icon = icon,
                                clusterName = clusterName,
                                displayOrder = displayOrderValue
                            )
                            else -> Panel.Button(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                label = label.ifBlank { "Button" },
                                brokerId = selectedBrokerId,
                                commandTopic = commandTopic,
                                payload = buttonPayload,
                                icon = icon,
                                clusterName = clusterName,
                                displayOrder = displayOrderValue
                            )
                        }
                        if (isEditing) {
                            app.configRepository.updatePanel(groupId, panel)
                            val oldClusterName = existing?.clusterName.orEmpty()
                            if (oldClusterName.isNotBlank() && oldClusterName != clusterName) {
                                app.configRepository.renameCluster(groupId, oldClusterName, clusterName)
                            }
                        } else {
                            app.configRepository.addPanelToGroup(groupId, panel)
                            // Signal back to HomeScreen which group to scroll to - a newly-added
                            // panel's cluster is likely to be scrolled out of view already.
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set("scrollToGroupId", groupId)
                        }
                        navController.popBackStack()
                    },
                    enabled = selectedBrokerId.isNotBlank() &&
                        (if (panelType == "Sensor") topic.isNotBlank() else commandTopic.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text(if (isEditing) "Save" else "Add") }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (config.brokers.isEmpty()) {
                Text("Add a broker first (Settings \u2192 Brokers) before creating panels.")
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = panelType == "Sensor",
                        onClick = { if (!isEditing) panelType = "Sensor" },
                        shape = SegmentedButtonDefaults.itemShape(0, 3)
                    ) { Text("Sensor") }
                    SegmentedButton(
                        selected = panelType == "Toggle",
                        onClick = { if (!isEditing) panelType = "Toggle" },
                        shape = SegmentedButtonDefaults.itemShape(1, 3)
                    ) { Text("Toggle") }
                    SegmentedButton(
                        selected = panelType == "Button",
                        onClick = { if (!isEditing) panelType = "Button" },
                        shape = SegmentedButtonDefaults.itemShape(2, 3)
                    ) { Text("Button") }
                }
                if (isEditing) {
                    Text(
                        "Panel type can't be changed once created \u2013 delete and re-add instead.",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                            })
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = clusterName,
                    onValueChange = { clusterName = it },
                    label = { Text("Cluster name (optional)") },
                    placeholder = { Text("e.g. Soil Sensor 1 \u2013 groups this with other panels of the same name") },
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = displayOrderText,
                    onValueChange = { displayOrderText = it.filter { c -> c.isDigit() } },
                    label = { Text("Display order (optional)") },
                    placeholder = { Text("Lower numbers appear first within the group") },
                    keyboardOptions = tvAwareKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number)),
                    modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                )

                Spacer(Modifier.height(16.dp))
                var iconExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = iconExpanded, onExpandedChange = { iconExpanded = it }) {
                    OutlinedTextField(
                        readOnly = true,
                        value = icon.name,
                        onValueChange = {},
                        label = { Text("Icon") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = iconExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = iconExpanded, onDismissRequest = { iconExpanded = false }) {
                        TileIcon.entries.forEach { ic ->
                            DropdownMenuItem(text = { Text(ic.name) }, onClick = { icon = ic; iconExpanded = false })
                        }
                    }
                }

                when (panelType) {
                    "Sensor" -> {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = topic,
                            onValueChange = { topic = it },
                            label = { Text("Topic") },
                            placeholder = { Text("e.g. zigbee2mqtt/Soil Sensor 1") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = jsonPath,
                            onValueChange = { jsonPath = it },
                            label = { Text("JSON field (blank = raw payload)") },
                            placeholder = { Text("e.g. temperature or state.battery") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Unit (optional)") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = decimalsText,
                            onValueChange = { decimalsText = it.filter { c -> c.isDigit() } },
                            label = { Text("Decimal places") },
                            placeholder = { Text("e.g. 0 to round to the nearest whole number") },
                            keyboardOptions = tvAwareKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number)),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = idealRangeTopic,
                            onValueChange = { idealRangeTopic = it },
                            label = { Text("Ideal range topic (optional)") },
                            placeholder = { Text("e.g. z2m2/SoilSensor_01/ideal \u2013 publishes {\"min\":x,\"max\":y}") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        if (idealRangeTopic.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = idealMinPath,
                                    onValueChange = { idealMinPath = it },
                                    label = { Text("Min field name") },
                                    keyboardOptions = tvAwareKeyboardOptions(),
                                    modifier = Modifier.weight(1f).clearFocusOnBack()
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = idealMaxPath,
                                    onValueChange = { idealMaxPath = it },
                                    label = { Text("Max field name") },
                                    keyboardOptions = tvAwareKeyboardOptions(),
                                    modifier = Modifier.weight(1f).clearFocusOnBack()
                                )
                            }
                            Text(
                                "Only needed if the topic doesn't use plain \"min\"/\"max\" keys \u2013 " +
                                        "e.g. set these to \"moisture_min\"/\"moisture_max\" for a shared device config topic.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "When set, this tile flashes red below min, green within range, and blue above max.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    "Toggle" -> {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = commandTopic,
                            onValueChange = { commandTopic = it },
                            label = { Text("Command topic") },
                            placeholder = { Text("e.g. zigbee2mqtt/Kitchen Plug/set") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = onPayload,
                                onValueChange = { onPayload = it },
                                label = { Text("ON payload") },
                                keyboardOptions = tvAwareKeyboardOptions(),
                                modifier = Modifier.weight(1f).clearFocusOnBack()
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = offPayload,
                                onValueChange = { offPayload = it },
                                label = { Text("OFF payload") },
                                keyboardOptions = tvAwareKeyboardOptions(),
                                modifier = Modifier.weight(1f).clearFocusOnBack()
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = stateTopic,
                            onValueChange = { stateTopic = it },
                            label = { Text("State topic (optional)") },
                            placeholder = { Text("e.g. zigbee2mqtt/Kitchen Plug") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = stateJsonPath,
                            onValueChange = { stateJsonPath = it },
                            label = { Text("State JSON field (optional)") },
                            placeholder = { Text("e.g. state") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                    }
                    else -> {
                        // Button
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = commandTopic,
                            onValueChange = { commandTopic = it },
                            label = { Text("Command topic") },
                            placeholder = { Text("e.g. zigbee2mqtt/Blind_01/set") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = buttonPayload,
                            onValueChange = { buttonPayload = it },
                            label = { Text("Payload") },
                            placeholder = { Text("e.g. {\"state\": \"STOP\"} or a bare value like STOP") },
                            keyboardOptions = tvAwareKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                        )
                        Text(
                            "Sent every time this button is tapped - there's no on/off state to track.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (existing != null) {
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Delete panel") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete panel?") },
            text = { Text("This removes \"${existing.label}\" from the dashboard.") },
            confirmButton = {
                TextButton(onClick = {
                    app.configRepository.removePanel(groupId, existing.id)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
