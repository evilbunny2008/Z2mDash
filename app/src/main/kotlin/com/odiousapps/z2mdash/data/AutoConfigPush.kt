package com.odiousapps.z2mdash.data

import com.odiousapps.z2mdash.Z2mDashApplication

/**
 * Pushes local edits (label/cluster renames, cross-group moves) back into the affected
 * auto-configured device's retained "/app" payload, so the payload - not just local config.json -
 * stays the source of truth those clusters/panels were built from. Manually-added panels have no
 * appConfigTopic to write to at all, so these are no-ops for anything that isn't auto-configured.
 *
 * Shared between AddPanelScreen (label/cluster-name edits) and HomeScreen (cross-group cluster
 * drag, group rename), unlike the order-push helpers in HomeScreen.kt which only ever fire from
 * drag gestures there.
 */

private fun deviceFor(app: Z2mDashApplication, panelId: String) =
    app.configRepository.config.value.autoConfiguredDevices.find { panelId in it.createdPanelIds }

private fun currentPayloadFor(app: Z2mDashApplication, device: AutoConfiguredDevice): String? =
    app.connectionManager.latestPayloads.value["${device.brokerId}|${device.appConfigTopic}"]

/** A device's own panels, in the order its payload originally declared them - needed to resolve a duplicate field/command-topic to the right array index (see SensorDiscovery.sensorFieldIndex/controlIndex). */
private fun orderedPanelsOf(app: Z2mDashApplication, device: AutoConfiguredDevice): List<Panel> {
    val panelsById = app.configRepository.config.value.groups.asSequence().flatMap { it.panels }.associateBy { it.id }
    return device.createdPanelIds.mapNotNull { panelsById[it] }
}

private fun publishAndMarkApplied(app: Z2mDashApplication, device: AutoConfiguredDevice, updatedPayload: String) {
    app.connectionManager.publish(device.brokerId, device.appConfigTopic, updatedPayload, retain = true)
    // Same reasoning as the order-push helpers in HomeScreen.kt: pre-marks the payload as
    // already applied so the "#"-subscribed echo of our own publish doesn't trigger a
    // redundant (though harmless/idempotent) reconcile pass.
    app.configRepository.markAutoConfiguredDevicePayloadApplied(
        device.brokerId, device.appConfigTopic, updatedPayload, device.lastKnownOrderVersion
    )
}

/**
 * Pushes [panel]'s new label into its device's retained payload, if it's auto-configured.
 * No-op (returns silently) for a manually-added panel.
 */
fun pushLabelUpdateIfAutoConfigured(app: Z2mDashApplication, panel: Panel) {
    val device = deviceFor(app, panel.id) ?: return
    val currentPayload = currentPayloadFor(app, device) ?: return
    val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return
    val orderedPanels = orderedPanelsOf(app, device)
    val updatedPayload = when (panel) {
        is Panel.Sensor -> {
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel) ?: return
            SensorDiscovery.updateDescriptionInAppPayload(currentPayload, fieldLabelUpdates = mapOf(index to panel.label))
        }
        is Panel.Toggle, is Panel.Button -> {
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel) ?: return
            SensorDiscovery.updateDescriptionInAppPayload(currentPayload, controlLabelUpdates = mapOf(index to panel.label))
        }
    } ?: return
    publishAndMarkApplied(app, device, updatedPayload)
}

/**
 * Pushes a single panel's own cluster override into its device's retained payload, if it's
 * auto-configured - the counterpart to ConfigRepository.movePanelToOwnCluster (dragging a panel
 * out of a shared cluster into its own). Without this, the next reconciliation pass (any future
 * republish of the device's payload) would rebuild that panel back into its old shared cluster,
 * silently undoing the drag. No-op for a manually-added panel.
 */
fun pushPanelClusterOverrideIfAutoConfigured(app: Z2mDashApplication, panel: Panel, newClusterName: String) {
    val device = deviceFor(app, panel.id) ?: return
    val currentPayload = currentPayloadFor(app, device) ?: return
    val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return
    val orderedPanels = orderedPanelsOf(app, device)
    val updatedPayload = when (panel) {
        is Panel.Sensor -> {
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel) ?: return
            SensorDiscovery.updateDescriptionInAppPayload(currentPayload, fieldClusterUpdates = mapOf(index to newClusterName))
        }
        is Panel.Toggle, is Panel.Button -> {
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel) ?: return
            SensorDiscovery.updateDescriptionInAppPayload(currentPayload, controlClusterUpdates = mapOf(index to newClusterName))
        }
    } ?: return
    publishAndMarkApplied(app, device, updatedPayload)
}

/**
 * Pushes a cluster rename into every auto-configured device that owns one of [renamedPanelIds]
 * (all now sharing [newClusterName] locally). For a field/control that had its own explicit
 * cluster override, that override is renamed; for one that was only using the device's own
 * "name" as its cluster (the fallback every field/control without an override uses), the
 * device's "name" itself is renamed instead - both can apply to the same device at once.
 */
fun pushClusterRenameForAutoConfiguredDevices(
    app: Z2mDashApplication,
    renamedPanelIds: List<String>,
    oldClusterName: String,
    newClusterName: String
) {
    val config = app.configRepository.config.value
    val panelsById = config.groups.asSequence().flatMap { it.panels }.associateBy { it.id }
    val panelIdsByDevice = config.autoConfiguredDevices.mapNotNull { device ->
        val owned = renamedPanelIds.filter { it in device.createdPanelIds }
        if (owned.isEmpty()) null else device to owned
    }

    panelIdsByDevice.forEach { (device, ownedPanelIds) ->
        val currentPayload = currentPayloadFor(app, device) ?: return@forEach
        val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return@forEach
        val orderedPanels = orderedPanelsOf(app, device)
        val deviceClusterName = deviceConfig.name.ifBlank { device.sensorTopic.substringAfterLast("/") }

        val fieldClusterUpdates = mutableMapOf<Int, String>()
        val controlClusterUpdates = mutableMapOf<Int, String>()
        var renameDeviceNameToo = false

        ownedPanelIds.forEach { panelId ->
            val panel = panelsById[panelId] ?: return@forEach
            when (panel) {
                is Panel.Sensor -> {
                    val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel) ?: return@forEach
                    val override = deviceConfig.panelClusters.getOrNull(index)?.takeIf { it.isNotBlank() }
                    if (override != null) {
                        if (override == oldClusterName) fieldClusterUpdates[index] = newClusterName
                    } else if (deviceClusterName == oldClusterName) {
                        renameDeviceNameToo = true
                    }
                }
                is Panel.Toggle, is Panel.Button -> {
                    val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel) ?: return@forEach
                    val override = deviceConfig.controls.getOrNull(index)?.cluster?.takeIf { it.isNotBlank() }
                    if (override != null) {
                        if (override == oldClusterName) controlClusterUpdates[index] = newClusterName
                    } else if (deviceClusterName == oldClusterName) {
                        renameDeviceNameToo = true
                    }
                }
            }
        }

        if (fieldClusterUpdates.isEmpty() && controlClusterUpdates.isEmpty() && !renameDeviceNameToo) return@forEach

        val updatedPayload = SensorDiscovery.updateDescriptionInAppPayload(
            currentPayload,
            fieldClusterUpdates = fieldClusterUpdates,
            controlClusterUpdates = controlClusterUpdates,
            newDeviceName = if (renameDeviceNameToo) newClusterName else null
        ) ?: return@forEach
        publishAndMarkApplied(app, device, updatedPayload)
    }
}

/**
 * Pushes a cluster's move to a different top-level group into every auto-configured device that
 * owns one of [movedPanelIds]. A device's payload has one shared "group" for every cluster it
 * describes, so this moves the whole device - every cluster it describes - to [newGroupName].
 */
fun pushGroupMoveForAutoConfiguredDevices(app: Z2mDashApplication, movedPanelIds: List<String>, newGroupName: String) {
    val config = app.configRepository.config.value
    val devices = config.autoConfiguredDevices.filter { device -> movedPanelIds.any { it in device.createdPanelIds } }
    devices.forEach { device ->
        val currentPayload = currentPayloadFor(app, device) ?: return@forEach
        val updatedPayload = SensorDiscovery.updateDescriptionInAppPayload(currentPayload, newGroup = newGroupName)
            ?: return@forEach
        publishAndMarkApplied(app, device, updatedPayload)
    }
}

/**
 * Pushes a top-level group rename into every auto-configured device whose payload currently
 * declares [oldGroupName] as its "group" (matched the same case-insensitive way
 * DeviceAutoConfigManager.resolveTargetGroupId reads it).
 */
fun pushGroupRenameForAutoConfiguredDevices(app: Z2mDashApplication, oldGroupName: String, newGroupName: String) {
    val config = app.configRepository.config.value
    config.autoConfiguredDevices.forEach { device ->
        val currentPayload = currentPayloadFor(app, device) ?: return@forEach
        val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return@forEach
        if (!deviceConfig.group.equals(oldGroupName, ignoreCase = true)) return@forEach
        val updatedPayload = SensorDiscovery.updateDescriptionInAppPayload(currentPayload, newGroup = newGroupName)
            ?: return@forEach
        publishAndMarkApplied(app, device, updatedPayload)
    }
}
