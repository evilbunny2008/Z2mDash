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
 * Pushes [panel]'s removal into its device's retained payload, if it's auto-configured - the
 * delete counterpart to pushLabelUpdateIfAutoConfigured, and the fix for a deleted panel otherwise
 * silently reappearing. Without this, deleting just one panel out of a multi-panel auto-configured
 * device (e.g. a duplicated cluster with several fields/controls) leaves the device's retained
 * "/app" payload still declaring the deleted field/control - so the very next time anything
 * reconciles that device (even the echo of a later edit to a sibling panel), buildPanels silently
 * recreates it as if it were brand new, undoing the delete. No-op for a manually-added panel.
 *
 * Must be called BEFORE the panel is actually removed from local config (e.g.
 * ConfigRepository.removePanel) - it resolves [panel]'s index by matching it among the device's
 * still-intact local panels (see sensorFieldIndex/controlIndex), which requires [panel] to still
 * be present there.
 */
fun pushPanelRemovalIfAutoConfigured(app: Z2mDashApplication, panel: Panel) {
    val device = deviceFor(app, panel.id) ?: return
    val currentPayload = currentPayloadFor(app, device) ?: return
    val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return
    val orderedPanels = orderedPanelsOf(app, device)
    val orderVersion = System.currentTimeMillis()
    val updatedPayload = when (panel) {
        is Panel.Sensor -> {
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel) ?: return
            SensorDiscovery.removeSensorFieldFromAppPayload(currentPayload, index, orderVersion)
        }
        is Panel.Toggle, is Panel.Button -> {
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel) ?: return
            SensorDiscovery.removeControlFromAppPayload(currentPayload, index, orderVersion)
        }
    } ?: return
    publishAndMarkApplied(app, device, updatedPayload)
    // Keeps createdPanelIds in sync too - without this, the device would still "own" a panel id
    // that no longer exists in any group, which is exactly what let it reappear in the first
    // place. A no-op if this was the device's last panel: the caller's own removePanel call
    // already drops the whole tracking entry (and clears the retained topic) in that case.
    app.configRepository.pruneAutoConfiguredDevicePanelId(device.brokerId, device.appConfigTopic, panel.id)
}

/** An update map for [index] when [oldValue] and [newValue] differ, else empty - used by pushPanelDetailsIfAutoConfigured to only push fields that actually changed. */
private fun <T> diffMap(index: Int, oldValue: T, newValue: T): Map<Int, T> =
    if (oldValue != newValue) mapOf(index to newValue) else emptyMap()

/**
 * Pushes every appearance/detail field that differs between [oldPanel] and [newPanel] into the
 * owning device's retained payload, if it's auto-configured: unit, icon, decimals, and ideal-range
 * topic/min-path/max-path for a Sensor; command topic, on/off payload, state topic/field, and icon
 * for a Toggle; command topic, payload, and icon for a Button. Label and cluster name are pushed
 * separately (pushLabelUpdateIfAutoConfigured, and the cluster-rename path in AddPanelScreen, since
 * a cluster rename cascades to every panel sharing the old name, not just this one).
 *
 * Without this, editing any of these fields on an auto-configured panel (e.g. one of several tiles
 * duplicated from a device's own published config) only changed local config.json - so the next
 * time anything reconciled that device (even the echo of an unrelated sibling panel's own edit),
 * buildPanels silently recomputed the field's suggested default from its raw name, discarding the
 * edit. No-op for a manually-added panel, or when nothing covered here actually changed.
 *
 * Must be called with [newPanel] already reflecting what's live in local config (i.e. after
 * ConfigRepository.updatePanel) - it resolves the panel's index by matching [newPanel]'s id among
 * the device's current local panels, same as pushLabelUpdateIfAutoConfigured.
 */
fun pushPanelDetailsIfAutoConfigured(app: Z2mDashApplication, oldPanel: Panel, newPanel: Panel) {
    val device = deviceFor(app, newPanel.id) ?: return
    val currentPayload = currentPayloadFor(app, device) ?: return
    val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return
    val orderedPanels = orderedPanelsOf(app, device)

    val updatedPayload = when (newPanel) {
        is Panel.Sensor -> {
            val old = oldPanel as? Panel.Sensor ?: return
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, newPanel) ?: return
            val unitUpdate = diffMap(index, old.unit, newPanel.unit)
            val iconUpdate = diffMap(index, old.icon.name, newPanel.icon.name)
            val decimalUpdate = diffMap(index, old.decimals, newPanel.decimals)
            val idealTopicUpdate = diffMap(index, old.idealRangeTopic, newPanel.idealRangeTopic)
            val idealMinUpdate = diffMap(index, old.idealMinPath, newPanel.idealMinPath)
            val idealMaxUpdate = diffMap(index, old.idealMaxPath, newPanel.idealMaxPath)
            if (unitUpdate.isEmpty() && iconUpdate.isEmpty() && decimalUpdate.isEmpty() &&
                idealTopicUpdate.isEmpty() && idealMinUpdate.isEmpty() && idealMaxUpdate.isEmpty()
            ) {
                return
            }
            SensorDiscovery.updateDescriptionInAppPayload(
                currentPayload,
                fieldUnitUpdates = unitUpdate,
                fieldIconUpdates = iconUpdate,
                fieldDecimalUpdates = decimalUpdate,
                fieldIdealTopicUpdates = idealTopicUpdate,
                fieldIdealMinPathUpdates = idealMinUpdate,
                fieldIdealMaxPathUpdates = idealMaxUpdate
            )
        }
        is Panel.Toggle -> {
            val old = oldPanel as? Panel.Toggle ?: return
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, newPanel) ?: return
            val commandTopicUpdate = diffMap(index, old.commandTopic, newPanel.commandTopic)
            val onPayloadUpdate = diffMap(index, old.onPayload, newPanel.onPayload)
            val offPayloadUpdate = diffMap(index, old.offPayload, newPanel.offPayload)
            val stateTopicUpdate = diffMap(index, old.stateTopic, newPanel.stateTopic)
            val stateFieldUpdate = diffMap(index, old.stateJsonPath, newPanel.stateJsonPath)
            val iconUpdate = diffMap(index, old.icon.name, newPanel.icon.name)
            if (commandTopicUpdate.isEmpty() && onPayloadUpdate.isEmpty() && offPayloadUpdate.isEmpty() &&
                stateTopicUpdate.isEmpty() && stateFieldUpdate.isEmpty() && iconUpdate.isEmpty()
            ) {
                return
            }
            SensorDiscovery.updateDescriptionInAppPayload(
                currentPayload,
                controlCommandTopicUpdates = commandTopicUpdate,
                controlOnPayloadUpdates = onPayloadUpdate,
                controlOffPayloadUpdates = offPayloadUpdate,
                controlStateTopicUpdates = stateTopicUpdate,
                controlStateFieldUpdates = stateFieldUpdate,
                controlIconUpdates = iconUpdate
            )
        }
        is Panel.Button -> {
            val old = oldPanel as? Panel.Button ?: return
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, newPanel) ?: return
            val commandTopicUpdate = diffMap(index, old.commandTopic, newPanel.commandTopic)
            val payloadUpdate = diffMap(index, old.payload, newPanel.payload)
            val iconUpdate = diffMap(index, old.icon.name, newPanel.icon.name)
            if (commandTopicUpdate.isEmpty() && payloadUpdate.isEmpty() && iconUpdate.isEmpty()) return
            SensorDiscovery.updateDescriptionInAppPayload(
                currentPayload,
                controlCommandTopicUpdates = commandTopicUpdate,
                controlOnPayloadUpdates = payloadUpdate,
                controlIconUpdates = iconUpdate
            )
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

/**
 * Clears the retained "/app" topic for every auto-configured device that was tracked in
 * [devicesBefore] but no longer appears in the current config - i.e. one whose last panel a
 * ConfigRepository call like removePanel/removePanels/deleteGroup just removed (those already
 * prune the device's tracking entry themselves). Call this right after such a removal, passing
 * the autoConfiguredDevices list captured immediately before it.
 *
 * Without this, the device's retained payload lingers on the broker after its panels are deleted
 * locally, and the very next reconcile pass - this app's own "#" subscription sees the still-
 * retained payload as an untracked "/app" topic again - silently recreates the panels right back.
 * With a broker that auto-accepts discovered devices, that makes a deleted cluster reappear
 * almost instantly, as if the delete never happened.
 */
fun clearRetainedAppTopicsForOrphanedDevices(app: Z2mDashApplication, devicesBefore: List<AutoConfiguredDevice>) {
    val stillTrackedKeys = app.configRepository.config.value.autoConfiguredDevices
        .map { "${it.brokerId}|${it.appConfigTopic}" }.toSet()
    devicesBefore
        .filter { "${it.brokerId}|${it.appConfigTopic}" !in stillTrackedKeys }
        .forEach { device -> app.connectionManager.publish(device.brokerId, device.appConfigTopic, "", retain = true) }
}

/**
 * Publishes a fresh "<topic>/app" retained payload for [clusterName]'s panels (in [groupId]) if
 * their shared topic doesn't already have one - e.g. right after a duplicated cluster is
 * retargeted at a topic nothing has ever described. Without this, a cluster that only ever
 * existed by duplication stays purely local: invisible to any other phone sharing the broker,
 * and unable to benefit from the same rename/reorder/move pushes every other auto-configured
 * cluster gets. Also registers the cluster as an auto-configured device locally, so this phone's
 * own reconcile pass recognises its own echo instead of re-detecting it as a brand-new pending
 * device. A no-op if the panels don't share one clean common topic (see
 * SensorDiscovery.commonTopicPrefix - nothing coherent to publish under), or if that topic's
 * "/app" is already retained by something else (never overwrites an existing config).
 */
fun publishAppTopicForClusterIfMissing(
    app: Z2mDashApplication,
    groupId: String,
    clusterName: String,
    // Seeds a Sensor field whose own topic *is* the new "/app" topic (e.g. a min/max threshold)
    // with a starting value, keyed by that field's (new, already-cloned) panel id - see
    // SensorDiscovery.buildAppConfigPayload's own doc for why this is needed at all.
    seedValuesByPanelId: Map<String, String> = emptyMap()
) {
    val config = app.configRepository.config.value
    val group = config.groups.find { it.id == groupId } ?: return
    val panels = group.panels.filter { it.clusterName == clusterName }
    if (panels.isEmpty()) return
    val topic = SensorDiscovery.commonTopicPrefix(panels)
    if (topic.isBlank()) return
    val brokerId = panels.first().brokerId
    val appTopic = "$topic/app"
    if (app.connectionManager.latestPayloads.value["$brokerId|$appTopic"] != null) return

    val payload = SensorDiscovery.buildAppConfigPayload(
        panels, clusterName, group.name, appTopic, seedValuesByPanelId
    )
    app.connectionManager.publish(brokerId, appTopic, payload, retain = true)
    app.configRepository.registerAutoConfiguredDevice(
        AutoConfiguredDevice(
            brokerId = brokerId,
            sensorTopic = topic,
            appConfigTopic = appTopic,
            lastAppliedPayload = payload,
            createdPanelIds = panels.map { it.id }
        )
    )
}
