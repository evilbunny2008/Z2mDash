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

internal fun deviceFor(app: Z2mDashApplication, panelId: String) =
    app.configRepository.config.value.autoConfiguredDevices.find { panelId in it.createdPanelIds }

internal fun currentPayloadFor(app: Z2mDashApplication, device: AutoConfiguredDevice): String? =
    app.connectionManager.latestPayloads.value["${device.brokerId}|${device.appConfigTopic}"]

/** A device's own panels, in the order its payload originally declared them - needed to resolve a duplicate field/command-topic to the right array index (see SensorDiscovery.sensorFieldIndex/controlIndex). */
internal fun orderedPanelsOf(app: Z2mDashApplication, device: AutoConfiguredDevice): List<Panel> {
    val panelsById = app.configRepository.config.value.groups.asSequence().flatMap { it.panels }.associateBy { it.id }
    return device.createdPanelIds.mapNotNull { panelsById[it] }
}

// [orderVersion] defaults to the device's own last-known value (a no-op maxOf), correct for
// every caller here except pushPanelRemovalIfAutoConfigured: that one actually stamps a fresh
// order_version into the payload it publishes (removeSensorFieldFromAppPayload/
// removeControlFromAppPayload), so it must pass that same value through here too - otherwise
// this recorded device.lastKnownOrderVersion stays stale while the just-published payload's own
// order_version has already moved past it, and the reconcile path's early-return only guards
// against the *exact same* lastAppliedPayload string coming back (see
// DeviceAutoConfigManager.reconcileKnownDevices), not a genuinely different later push landing
// while the stale order_version is still on record.
private fun publishAndMarkApplied(
    app: Z2mDashApplication,
    device: AutoConfiguredDevice,
    updatedPayload: String,
    orderVersion: Long = device.lastKnownOrderVersion
) {
    app.connectionManager.publish(device.brokerId, device.appConfigTopic, updatedPayload, retain = true)
    // Same reasoning as the order-push helpers in HomeScreen.kt: pre-marks the payload as
    // already applied so the "#"-subscribed echo of our own publish doesn't trigger a
    // redundant (though harmless/idempotent) reconcile pass.
    app.configRepository.markAutoConfiguredDevicePayloadApplied(
        device.brokerId, device.appConfigTopic, updatedPayload, orderVersion
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
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel.id, panel.jsonPath) ?: return
            SensorDiscovery.updateDescriptionInAppPayload(currentPayload, fieldLabelUpdates = mapOf(index to panel.label))
        }
        is Panel.Toggle, is Panel.Button -> {
            val commandTopic = commandTopicOf(panel) ?: return
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel.id, commandTopic) ?: return
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
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel.id, panel.jsonPath) ?: return
            SensorDiscovery.removeSensorFieldFromAppPayload(currentPayload, index, orderVersion)
        }
        is Panel.Toggle, is Panel.Button -> {
            val commandTopic = commandTopicOf(panel) ?: return
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel.id, commandTopic) ?: return
            SensorDiscovery.removeControlFromAppPayload(currentPayload, index, orderVersion)
        }
    } ?: return
    publishAndMarkApplied(app, device, updatedPayload, orderVersion)
    // Keeps createdPanelIds in sync too - without this, the device would still "own" a panel id
    // that no longer exists in any group, which is exactly what let it reappear in the first
    // place. A no-op if this was the device's last panel: the caller's own removePanel call
    // already drops the whole tracking entry (and clears the retained topic) in that case.
    app.configRepository.pruneAutoConfiguredDevicePanelId(device.brokerId, device.appConfigTopic, panel.id)
}

/** An update map for [index] when [oldValue] and [newValue] differ, else empty - used by pushPanelDetailsIfAutoConfigured to only push fields that actually changed. */
private fun <T> diffMap(index: Int, oldValue: T, newValue: T): Map<Int, T> =
    if (oldValue != newValue) mapOf(index to newValue) else emptyMap()

/** A Toggle/Button's own commandTopic, or null for a Sensor - the identity SensorDiscovery.controlIndex matches on. */
private fun commandTopicOf(panel: Panel): String? = when (panel) {
    is Panel.Toggle -> panel.commandTopic
    is Panel.Button -> panel.commandTopic
    is Panel.Sensor -> null
}

/**
 * Pushes every appearance/detail field that differs between [oldPanel] and [newPanel] into the
 * owning device's retained payload, if it's auto-configured: unit, icon, decimals, own reading
 * topic, and ideal-range topic/min-path/max-path for a Sensor; command topic, on/off payload,
 * state topic/field, and icon for a Toggle; command topic, payload, and icon for a Button. Label
 * and cluster name are pushed
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
            // Matched on the OLD jsonPath - see sensorFieldIndex's own doc on why using the
            // already-edited new jsonPath here would always miss and silently drop the whole
            // update (not just a field rename), the same bug class as controlIndex's own doc.
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, newPanel.id, old.jsonPath) ?: return
            val jsonPathUpdate = diffMap(index, old.jsonPath, newPanel.jsonPath)
            val unitUpdate = diffMap(index, old.unit, newPanel.unit)
            val iconUpdate = diffMap(index, old.icon.name, newPanel.icon.name)
            val decimalUpdate = diffMap(index, old.decimals, newPanel.decimals)
            val topicUpdate = diffMap(index, old.topic, newPanel.topic)
            val idealTopicUpdate = diffMap(index, old.idealRangeTopic, newPanel.idealRangeTopic)
            val idealMinUpdate = diffMap(index, old.idealMinPath, newPanel.idealMinPath)
            val idealMaxUpdate = diffMap(index, old.idealMaxPath, newPanel.idealMaxPath)
            if (jsonPathUpdate.isEmpty() && unitUpdate.isEmpty() && iconUpdate.isEmpty() && decimalUpdate.isEmpty() &&
                topicUpdate.isEmpty() && idealTopicUpdate.isEmpty() && idealMinUpdate.isEmpty() && idealMaxUpdate.isEmpty()
            ) {
                return
            }
            SensorDiscovery.updateDescriptionInAppPayload(
                currentPayload,
                fieldJsonPathUpdates = jsonPathUpdate,
                fieldUnitUpdates = unitUpdate,
                fieldIconUpdates = iconUpdate,
                fieldDecimalUpdates = decimalUpdate,
                fieldTopicUpdates = topicUpdate,
                fieldIdealTopicUpdates = idealTopicUpdate,
                fieldIdealMinPathUpdates = idealMinUpdate,
                fieldIdealMaxPathUpdates = idealMaxUpdate
            )
        }
        is Panel.Toggle -> {
            val old = oldPanel as? Panel.Toggle ?: return
            // Matched on the OLD commandTopic (deviceConfig was parsed from the still-old
            // retained payload) - see controlIndex's own doc on why using the already-edited new
            // topic here would always miss and silently drop the whole update.
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, newPanel.id, old.commandTopic) ?: return
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
            // Matched on the OLD commandTopic - see the Toggle branch above / controlIndex's own doc.
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, newPanel.id, old.commandTopic) ?: return
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
            val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel.id, panel.jsonPath) ?: return
            SensorDiscovery.updateDescriptionInAppPayload(currentPayload, fieldClusterUpdates = mapOf(index to newClusterName))
        }
        is Panel.Toggle, is Panel.Button -> {
            val commandTopic = commandTopicOf(panel) ?: return
            val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel.id, commandTopic) ?: return
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
                    val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedPanels, panel.id, panel.jsonPath) ?: return@forEach
                    val override = deviceConfig.panelClusters.getOrNull(index)?.takeIf { it.isNotBlank() }
                    if (override != null) {
                        if (override == oldClusterName) fieldClusterUpdates[index] = newClusterName
                    } else if (deviceClusterName == oldClusterName) {
                        renameDeviceNameToo = true
                    }
                }
                is Panel.Toggle, is Panel.Button -> {
                    val commandTopic = commandTopicOf(panel) ?: return@forEach
                    val index = SensorDiscovery.controlIndex(deviceConfig, orderedPanels, panel.id, commandTopic) ?: return@forEach
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
    // Registered *before* publishing, not after - the MQTT echo of this exact publish can arrive
    // back (via connectionManager.latestPayloads, on a background dispatcher) before the very
    // next line here would otherwise have run, and DeviceAutoConfigManager.detectNewDevices scans
    // every "/app" topic not yet in autoConfiguredDevices - so publishing first left a real, if
    // narrow, window where this phone's own echo raced ahead of its own registration and got
    // treated as an unknown device, duplicating every one of this cluster's panels.
    app.configRepository.registerAutoConfiguredDevice(
        AutoConfiguredDevice(
            brokerId = brokerId,
            sensorTopic = topic,
            appConfigTopic = appTopic,
            lastAppliedPayload = payload,
            createdPanelIds = panels.map { it.id }
        )
    )
    app.connectionManager.publish(brokerId, appTopic, payload, retain = true)
}

/**
 * Moves every panel sharing [clusterName] in [groupId] from [oldTopicPrefix] onto
 * [newTopicPrefix] - both the local rewrite (ConfigRepository.retopicCluster) and the MQTT side
 * (re-pointing this cluster's AutoConfiguredDevice tracking, carrying its "/app" payload forward
 * to the new topic with embedded control command/state topics rewritten too, and clearing the old
 * topic) - the single-cluster "Change topic" dialog's own confirm action, extracted so
 * [retopicGroupTopicPrefix] can drive the same sequence across every matching cluster in a group
 * in one pass. See HomeScreen's "Change topic" dialog for the original per-cluster doc on why the
 * embedded-topic rewrite and tracking re-point ordering matter.
 */
fun retopicClusterAndPublish(
    app: Z2mDashApplication,
    groupId: String,
    clusterName: String,
    oldTopicPrefix: String,
    newTopicPrefix: String
) {
    val clusterPanels = app.configRepository.config.value.groups
        .find { it.id == groupId }?.panels
        ?.filter { it.clusterName == clusterName } ?: emptyList()
    val brokerId = clusterPanels.firstOrNull()?.brokerId
    val device = app.configRepository.config.value.autoConfiguredDevices
        .find { d -> clusterPanels.any { it.id in d.createdPanelIds } }
    val oldAppTopic = "$oldTopicPrefix/app"
    val oldAppPayload = brokerId?.let { app.connectionManager.latestPayloads.value["$it|$oldAppTopic"] }
        ?.replace(oldTopicPrefix, newTopicPrefix)
    app.configRepository.retopicCluster(groupId, clusterName, oldTopicPrefix, newTopicPrefix)
    if (device != null) {
        val newSensorTopic = device.sensorTopic.replace(oldTopicPrefix, newTopicPrefix)
        app.configRepository.retopicAutoConfiguredDevice(
            device.brokerId, device.appConfigTopic, newSensorTopic, "$newTopicPrefix/app"
        )
    } else if (brokerId != null && oldAppPayload != null) {
        // No AutoConfiguredDevice was tracking this cluster's old topic (its createdPanelIds had
        // already drifted out of sync with clusterPanels, or it was never auto-configured to begin
        // with) even though an old "/app" payload existed there to carry forward. Without this,
        // the publish below would land at a "/app" topic nothing tracks, which
        // DeviceAutoConfigManager.detectNewDevices then treats as a brand-new device on every
        // single future reconcile - including every app restart's fresh resubscribe, since nothing
        // ever starts tracking it to make that check stop failing - silently auto-creating another
        // full duplicate copy of every panel in this cluster each time. Confirmed by a user report
        // of a single toggle multiplying into a dozen, still growing on every app reopen.
        // Registering tracking explicitly here, for the panels retopicCluster just moved onto
        // newTopicPrefix (which is this cluster's own new common topic, so doubles as its
        // sensorTopic), closes that gap the same way publishAppTopicForClusterIfMissing does for a
        // cluster that was never auto-configured at all.
        app.configRepository.registerAutoConfiguredDevice(
            AutoConfiguredDevice(
                brokerId = brokerId,
                sensorTopic = newTopicPrefix,
                appConfigTopic = "$newTopicPrefix/app",
                lastAppliedPayload = oldAppPayload,
                createdPanelIds = clusterPanels.map { it.id }
            )
        )
    }
    if (brokerId != null && oldAppPayload != null) {
        app.connectionManager.publish(brokerId, "$newTopicPrefix/app", oldAppPayload, retain = true)
    }
    if (device != null) {
        app.connectionManager.publish(device.brokerId, device.appConfigTopic, "", retain = true)
    }
}

/**
 * [retopicClusterAndPublish] for every named cluster in [groupId] whose own common topic (see
 * SensorDiscovery.commonTopicPrefix) is exactly [oldTopicPrefix] - the bulk counterpart, for
 * moving every cluster a Zigbee network split actually touched in one action instead of
 * repeating "Change topic" per cluster. A cluster with a mixed/inconsistent topic (commonTopicPrefix
 * blank) or already on a different topic is left untouched. A panel with no cluster name at all is
 * skipped too - retopicClusterAndPublish matches by clusterName, which a blank-named "standalone"
 * panel has none of to match back against. Returns the cluster names actually moved, for the
 * caller to report back to the user.
 */
fun retopicGroupTopicPrefix(app: Z2mDashApplication, groupId: String, oldTopicPrefix: String, newTopicPrefix: String): List<String> {
    val group = app.configRepository.config.value.groups.find { it.id == groupId } ?: return emptyList()
    val clusterBuckets = group.panels.filter { it.clusterName.isNotBlank() }.groupBy { it.clusterName }
    val matchingClusterNames = clusterBuckets.filterValues { panels ->
        SensorDiscovery.commonTopicPrefix(panels) == oldTopicPrefix
    }.keys.toList()
    matchingClusterNames.forEach { clusterName ->
        retopicClusterAndPublish(app, groupId, clusterName, oldTopicPrefix, newTopicPrefix)
    }
    return matchingClusterNames
}

/**
 * Rebuilds and republishes a fresh "<topic>/app" payload, from this phone's current local
 * config, for every cluster (and standalone panel, each treated as its own single-panel
 * "cluster") in [groupId] that has a coherent common topic - unlike
 * [publishAppTopicForClusterIfMissing], overwriting whatever's already retained rather than
 * only filling a gap. Meant for the "force upload" action on the group edit screen: the deliberate,
 * manual fix for a group/broker that's drifted out of sync (e.g. another phone sharing the same
 * broker published a stale or conflicting payload - see the multi-phone-deployment setup this app
 * expects), re-asserting this phone's local config.json as the canonical source of truth for
 * every device the group owns.
 *
 * A Sensor field whose own topic *is* the appTopic being republished (e.g. an editable min/max
 * threshold - see buildAppConfigPayload's own doc) has its current value read back out of the
 * existing retained payload first and re-seeded, so a force-republish doesn't silently reset a
 * threshold the user had set via that field's own edit action. If that read-back comes up empty -
 * this topic isn't in latestPayloads at all yet, e.g. right after a broker's base topic was just
 * widened to cover it and nothing's arrived from the broker for it yet - the whole cluster is left
 * untouched rather than published with buildAppConfigPayload's own "0" placeholder standing in for
 * an unknown real threshold: for an ordinary reading that "0" is harmless (it just shows "--" or
 * gets overwritten by the next real value), but for a moisture ideal-range min/max it collapses
 * the midpoint to 0, and since virtually any reading is "above" a midpoint of 0,
 * WateringAlertManager would score every subsequent reading as already above target - confirmed by
 * a user report of repeated spurious watering alerts right after a force-upload run against a
 * topic namespace that had only just been subscribed to.
 */
/**
 * [forceRepublishGroupAppTopics] for every group in the local config, one at a time - the "force
 * upload" action on the Settings screen, for when drift isn't confined to one group (e.g. after a
 * broker outage, or catching up a phone that's been offline for a while) and re-opening the edit
 * dialog for each group individually would be tedious. Returns how many groups were actually
 * processed, for the caller to report back to the user.
 */
fun forceRepublishAllGroupsAppTopics(app: Z2mDashApplication): Int {
    val groupIds = app.configRepository.config.value.groups.map { it.id }
    groupIds.forEach { groupId -> forceRepublishGroupAppTopics(app, groupId) }
    return groupIds.size
}

fun forceRepublishGroupAppTopics(app: Z2mDashApplication, groupId: String) {
    val config = app.configRepository.config.value
    val group = config.groups.find { it.id == groupId } ?: return
    val clusterBuckets = group.panels.groupBy { it.clusterName.ifBlank { "__single__${it.id}" } }

    clusterBuckets.values.forEach { panels ->
        val topic = SensorDiscovery.commonTopicPrefix(panels)
        if (topic.isBlank()) return@forEach
        val clusterName = panels.first().clusterName.ifBlank { panels.first().label }
        val brokerId = panels.first().brokerId
        val appTopic = "$topic/app"

        val currentPayload = app.connectionManager.latestPayloads.value["$brokerId|$appTopic"]
        val appTopicEmbeddedFields = panels.filterIsInstance<Panel.Sensor>().filter { it.topic == appTopic }
        val seedValuesByPanelId = appTopicEmbeddedFields
            .mapNotNull { panel ->
                val value = currentPayload?.let { JsonPath.extract(it, panel.jsonPath) } ?: return@mapNotNull null
                panel.id to value
            }.toMap()
        // See this function's own doc - a field that needed seeding but didn't get one means the
        // real current value is unknown, so this cluster is skipped rather than published with a
        // fabricated placeholder in its place.
        if (seedValuesByPanelId.size < appTopicEmbeddedFields.size) return@forEach

        val payload = SensorDiscovery.buildAppConfigPayload(
            panels, clusterName, group.name, appTopic, seedValuesByPanelId
        )
        // Registered before publishing - see publishAppTopicForClusterIfMissing's own comment on
        // why the order matters (this phone's own echo can otherwise race ahead of its own
        // registration and get mistaken for an unknown device).
        app.configRepository.registerAutoConfiguredDevice(
            AutoConfiguredDevice(
                brokerId = brokerId,
                sensorTopic = topic,
                appConfigTopic = appTopic,
                lastAppliedPayload = payload,
                createdPanelIds = panels.map { it.id }
            )
        )
        app.connectionManager.publish(brokerId, appTopic, payload, retain = true)
    }
}
