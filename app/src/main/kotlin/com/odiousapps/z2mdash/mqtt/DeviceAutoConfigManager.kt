package com.odiousapps.z2mdash.mqtt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.odiousapps.z2mdash.data.AutoConfiguredDevice
import com.odiousapps.z2mdash.data.ConfigRepository
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.PanelGroup
import com.odiousapps.z2mdash.data.PendingAutoConfigDevice
import com.odiousapps.z2mdash.data.SensorDiscovery
import com.odiousapps.z2mdash.data.StableIds
import com.odiousapps.z2mdash.data.withId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps auto-configured devices' panels in sync with their "<topic>/app" retained
 * payload (see SensorDiscovery/DiscoverScreen). Also watches every broker's "#"
 * subscription for new "<topic>/app" topics, queuing unknown ones as pending and
 * notifying the user.
 */
class DeviceAutoConfigManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectionManager: MqttConnectionManager
) {
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            connectionManager.latestPayloads.collect { payloads ->
                reconcileKnownDevices(payloads)
                detectNewDevices(payloads)
            }
        }
    }

    // "brokerId|appConfigTopic" -> the payload most recently confirmed to already carry every id
    // (see SensorDiscovery.isMissingIds/stampIdsInAppPayload), so an unchanged payload is only
    // parsed for that check once per session rather than on every incoming MQTT message.
    private val idsVerifiedPayloads = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun reconcileKnownDevices(payloads: Map<String, String>) {
        // The device list itself (which devices exist) is stable for the duration of one
        // reconcile pass - only detectNewDevices (called after this, per batch) adds to it.
        // But cfg.groups is NOT re-fetched per device below: an earlier device in this same
        // forEach can have already mutated it via applyDeviceAutoConfig, so every group/panel
        // lookup here re-reads configRepository.config.value fresh rather than closing over one
        // snapshot taken before the loop started - otherwise a later device's own diff would be
        // computed against a stale pre-batch view of cfg.groups.
        configRepository.config.value.autoConfiguredDevices.forEach { device ->
            val payloadKey = "${device.brokerId}|${device.appConfigTopic}"
            val currentPayload = payloads[payloadKey] ?: return@forEach
            if (currentPayload == device.lastAppliedPayload && idsVerifiedPayloads[payloadKey] == currentPayload) return@forEach

            val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return@forEach
            // An unchanged payload only needs reconciling at all if it still lacks ids - typically
            // the first run after upgrading, when every hand-written payload predates them. The
            // full pass below then stamps them in.
            if (currentPayload == device.lastAppliedPayload && !SensorDiscovery.isMissingIds(deviceConfig)) {
                idsVerifiedPayloads[payloadKey] = currentPayload
                return@forEach
            }
            val sensorPayload = payloads["${device.brokerId}|${device.sensorTopic}"]
            val sensorFieldKeys = sensorPayload?.let { SensorDiscovery.fieldKeysOf(it) } ?: emptySet()

            val ownedPanelIds = device.createdPanelIds.toSet()
            val currentGroupId = configRepository.config.value.groups
                .find { g -> g.panels.any { it.id in ownedPanelIds } }?.id
            val targetGroupId = configRepository.resolveOrCreateGroup(deviceConfig.groupId, deviceConfig.group, currentGroupId)
            var targetGroup = configRepository.config.value.groups.find { it.id == targetGroupId } ?: return@forEach
            // Same group (by id) but a different name than this phone has for it - another phone
            // renamed the group, so follow it here too.
            if (deviceConfig.groupId == targetGroupId && deviceConfig.group != null && deviceConfig.group != targetGroup.name) {
                targetGroup = targetGroup.copy(name = deviceConfig.group)
                configRepository.upsertGroup(targetGroup)
            }

            val reservedPanelIds = configRepository.config.value.groups.asSequence()
                .flatMap { it.panels }.map { it.id }.filter { it !in ownedPanelIds }.toSet()
            val built = SensorDiscovery.buildPanels(
                brokerId = device.brokerId,
                sensorTopic = device.sensorTopic,
                sensorFieldKeys = sensorFieldKeys,
                appConfigTopic = device.appConfigTopic,
                appConfigPayload = currentPayload,
                deviceConfig = deviceConfig,
                groupName = targetGroup.name,
                groupClusters = targetGroup.clusters,
                reservedPanelIds = reservedPanelIds
            )
            val builtPanels = built.panels
            if (builtPanels.isEmpty()) return@forEach

            val existingPanelsList = configRepository.config.value.groups.asSequence()
                .flatMap { it.panels }
                .filter { it.id in ownedPanelIds }
                .toList()
            val existingById = existingPanelsList.associateBy { it.id }
            val existingKeys = identityKeys(existingPanelsList)
            val existingPanels = existingPanelsList.associateBy { existingKeys.getValue(it) }
            val builtKeys = identityKeys(builtPanels)

            // A payload that drops most of what this device currently owns is trusted the same as
            // any smaller edit, not treated as suspicious - this is exactly what a deliberate,
            // legitimate "Force Upload" from another phone looks like on the wire (its entire
            // purpose is re-asserting that phone's config as canonical, potentially replacing most
            // or all of what's currently retained - see AutoConfigPush.forceRepublishGroupAppTopics'
            // own doc), and this reconciler has no way to tell that apart from a corrupted payload.
            // An earlier version of this function tried to guard against the latter by ignoring a
            // majority-shrink payload outright, but since it left lastAppliedPayload unset, and a
            // genuine Force Upload's retained payload never changes again afterward, that guard
            // could never be satisfied and permanently blocked a legitimate Force Upload from ever
            // reaching other phones - confirmed by a user report that force-uploading a group on
            // one phone never showed up on another.
            //
            // Only adopt the payload's order for an existing panel if its order_version is
            // strictly newer than what this phone last applied for the device - otherwise a
            // stale/retained redelivery (reconnect, or another phone not yet caught up) would
            // silently undo the user's local reorder. A brand-new panel always takes its order
            // from the payload.
            //
            // Ids: a payload-declared id always wins (that's what keeps every phone on the same
            // id). A legacy payload with none gets StableIds.legacyPanelId instead - derived from
            // the same identity key on every phone, so the ids each phone stamps back below agree.
            // Matching to the existing local panel (for order/editable carry-over) is by id first,
            // falling back to that identity key.
            val incomingOrderVersion = deviceConfig.orderVersion
            val adoptIncomingOrder = incomingOrderVersion != null && incomingOrderVersion > device.lastKnownOrderVersion
            val usedIds = mutableSetOf<String>()
            val newPanels = builtPanels.map { panel ->
                val identityKey = builtKeys.getValue(panel)
                val fromPayload = panel.id in built.payloadPanelIds
                val existing = (if (fromPayload) existingById[panel.id] else null) ?: existingPanels[identityKey]
                val id = when {
                    fromPayload -> panel.id
                    else -> StableIds.legacyPanelId(device.appConfigTopic, identityKey)
                        .takeIf { it !in reservedPanelIds }
                        ?: existing?.id
                        ?: panel.id
                }.let { candidate -> if (usedIds.add(candidate)) candidate else StableIds.newId().also { usedIds.add(it) } }
                if (existing == null) return@map panel.withId(id)
                // panel.displayOrder (just computed by SensorDiscovery.composedDisplayOrder) is
                // Int.MAX_VALUE whenever the payload has neither group_order nor a panel_order/
                // order entry for this field - that's "this payload carries no order information
                // at all", not a genuine instruction to sort the field last, so it's never
                // preferred over an already-known local position even when adoptIncomingOrder is
                // true. Without this, any device whose payload was ever published without
                // group_order/panel_order (its own echo of ANY edit still counts as a genuinely
                // newer order_version) would have every one of its panels silently reset to
                // Int.MAX_VALUE on the next reconcile, dropping the whole cluster to the bottom
                // of its group - confirmed by a user report reproduced via the editable-value
                // dialog (HomeScreen's "Edit Min/Max" panel), which publishes straight to its
                // own device's "/app" topic without going through AutoConfigPush's usual
                // publishAndMarkApplied bookkeeping, so its own echo always looked like a
                // genuinely new order_version to adopt.
                val displayOrder = if (adoptIncomingOrder && panel.displayOrder != Int.MAX_VALUE) {
                    panel.displayOrder
                } else {
                    existing.displayOrder
                }
                when (panel) {
                    // editable is a local-only UI preference (see Panel.Sensor's doc) with no
                    // representation in the device's own payload - preserved the same way
                    // displayOrder is, so a rebuilt panel doesn't silently lose it.
                    is Panel.Sensor -> panel.copy(
                        id = id,
                        displayOrder = displayOrder,
                        editable = (existing as? Panel.Sensor)?.editable ?: false
                    )
                    is Panel.Toggle -> panel.copy(id = id, displayOrder = displayOrder)
                    is Panel.Button -> panel.copy(id = id, displayOrder = displayOrder)
                }
            }

            // Same last-writer-wins gate as panel/cluster order above: only adopt this payload's
            // dashboard_order when its order_version is genuinely newer, so a stale retained
            // redelivery can't undo a more recent drag-reorder of the dashboard groups.
            val updatedDevice = device.copy(
                lastAppliedPayload = currentPayload,
                createdPanelIds = newPanels.map { it.id },
                lastKnownOrderVersion = if (adoptIncomingOrder) incomingOrderVersion else device.lastKnownOrderVersion,
                lastKnownDashboardOrder = if (adoptIncomingOrder) {
                    deviceConfig.dashboardOrder ?: device.lastKnownDashboardOrder
                } else {
                    device.lastKnownDashboardOrder
                }
            )
            configRepository.applyDeviceAutoConfig(
                oldPanelIds = ownedPanelIds,
                updatedDevice = updatedDevice,
                targetGroupId = targetGroupId,
                newPanels = newPanels,
                newClusters = built.clusters
            )
            configRepository.resyncDashboardGroupOrder()
            stampIdsIfMissing(updatedDevice, currentPayload, deviceConfig, newPanels, targetGroup)
        }
    }

    /**
     * Writes every id this phone just resolved for [device] (group, clusters, panels) back into
     * its retained "/app" payload if [payload] doesn't already carry them all - the "assign &
     * republish" half of legacy payload handling, so every phone sharing the broker converges on
     * the ids in the payload rather than each keeping its own. Pre-marked as applied (keeping the
     * device's existing order_version - ids aren't ordering), same as every other app-side
     * publish, so this phone's own echo is skipped. A no-op once the payload already matches.
     */
    private fun stampIdsIfMissing(
        device: AutoConfiguredDevice,
        payload: String,
        deviceConfig: SensorDiscovery.DeviceAppConfig,
        panels: List<Panel>,
        group: PanelGroup
    ) {
        val payloadKey = "${device.brokerId}|${device.appConfigTopic}"
        val stamped = SensorDiscovery.stampIdsInAppPayload(payload, deviceConfig, panels, group.id, group.name)
        if (stamped == null) {
            idsVerifiedPayloads[payloadKey] = payload
            return
        }
        connectionManager.publish(device.brokerId, device.appConfigTopic, stamped, retain = true)
        configRepository.markAutoConfiguredDevicePayloadApplied(
            device.brokerId, device.appConfigTopic, stamped, device.lastKnownOrderVersion
        )
        idsVerifiedPayloads[payloadKey] = stamped
    }

    /**
     * Stable panel identity independent of its random UUID, so a freshly rebuilt panel
     * (buildPanels always mints a new id) can be matched to its existing counterpart: sensors by
     * field, controls by command topic (plus state field, for a Toggle). A device can
     * legitimately declare the same field/command topic twice - e.g. one shared "linkquality"
     * value shown once per outlet, or two outlets whose commands both go to the same
     * "<state_topic>/set" but differ in payload/state_field, on a dual-outlet device. Those would
     * otherwise collide and get merged onto a single id by the caller, which is exactly what
     * broke independent drag-reordering of two such tiles. So the base key also folds in "which
     * occurrence of this base this is" (0-based, in list order) as a last-resort tie-breaker -
     * stable as long as colliding entries keep the same order relative to each other between
     * rebuilds, which this function is always called once per full list (both the existing panels
     * and the freshly built ones) precisely so that ordering lines up.
     */
    private fun identityKeys(panels: List<Panel>): Map<Panel, String> {
        val seenCount = mutableMapOf<String, Int>()
        fun withOccurrence(base: String): String {
            val occurrence = seenCount.getOrDefault(base, 0)
            seenCount[base] = occurrence + 1
            return "$base|$occurrence"
        }
        return panels.associateWith { panel ->
            when (panel) {
                is Panel.Sensor -> withOccurrence("sensor|${panel.topic}|${panel.jsonPath}")
                is Panel.Toggle -> withOccurrence("toggle|${panel.commandTopic}|${panel.stateJsonPath}")
                is Panel.Button -> withOccurrence("button|${panel.commandTopic}")
            }
        }
    }

    /** Scans every topic ending in "/app" for ones not yet tracked, pending, or dismissed. */
    private fun detectNewDevices(payloads: Map<String, String>) {
        val config = configRepository.config.value
        val trackedKeys = config.autoConfiguredDevices.map { "${it.brokerId}|${it.appConfigTopic}" }.toSet()
        val pendingKeys = config.pendingAutoConfigDevices.map { "${it.brokerId}|${it.appConfigTopic}" }.toSet()
        val ignoredKeys = config.ignoredAppConfigTopics.toSet()
        val brokersById = config.brokers.associateBy { it.id }

        payloads.forEach { (compositeKey, payload) ->
            val separatorIndex = compositeKey.indexOf('|')
            if (separatorIndex < 0) return@forEach
            val brokerId = compositeKey.substring(0, separatorIndex)
            val topic = compositeKey.substring(separatorIndex + 1)
            val broker = brokersById[brokerId] ?: return@forEach
            if (!topic.endsWith("/app")) return@forEach

            val key = "$brokerId|$topic"
            if (key in trackedKeys || key in pendingKeys || key in ignoredKeys) return@forEach

            val deviceConfig = SensorDiscovery.parseDeviceAppConfig(payload) ?: return@forEach
            val sensorTopic = topic.removeSuffix("/app")
            val deviceName = deviceConfig.name.ifBlank { sensorTopic.substringAfterLast("/") }

            if (broker.autoAcceptDiscoveredDevices) {
                autoAcceptDevice(brokerId, sensorTopic, topic, payload, deviceConfig, payloads)
                return@forEach
            }

            configRepository.addPendingAutoConfigDevice(
                PendingAutoConfigDevice(
                    brokerId = brokerId,
                    sensorTopic = sensorTopic,
                    appConfigTopic = topic,
                    deviceName = deviceName
                )
            )
            notifyNewDeviceFound(key, deviceName)
        }
    }

    /**
     * Same result as tapping "Add" on the pending-device banner (HomeScreen.addPendingDevice),
     * but built directly from the payload - used when the broker's autoAcceptDiscoveredDevices
     * is on. Group fallback order matches the manual path: declared group, then first existing
     * group, then a new "Discovered Sensors" group.
     */
    private fun autoAcceptDevice(
        brokerId: String,
        sensorTopic: String,
        appConfigTopic: String,
        appConfigPayload: String,
        deviceConfig: SensorDiscovery.DeviceAppConfig,
        payloads: Map<String, String>
    ) {
        val sensorPayload = payloads["$brokerId|$sensorTopic"]
        val sensorFieldKeys = sensorPayload?.let { SensorDiscovery.fieldKeysOf(it) } ?: emptySet()

        val targetGroupId = configRepository.resolveOrCreateGroup(deviceConfig.groupId, deviceConfig.group)
        val targetGroup = configRepository.config.value.groups.find { it.id == targetGroupId } ?: return
        val reservedPanelIds = configRepository.config.value.groups.asSequence()
            .flatMap { it.panels }.map { it.id }.toSet()
        val built = SensorDiscovery.buildPanels(
            brokerId = brokerId,
            sensorTopic = sensorTopic,
            sensorFieldKeys = sensorFieldKeys,
            appConfigTopic = appConfigTopic,
            appConfigPayload = appConfigPayload,
            deviceConfig = deviceConfig,
            groupName = targetGroup.name,
            groupClusters = targetGroup.clusters,
            reservedPanelIds = reservedPanelIds
        )
        val newPanels = built.panels
        if (newPanels.isEmpty()) return

        val device = AutoConfiguredDevice(
            brokerId = brokerId,
            sensorTopic = sensorTopic,
            appConfigTopic = appConfigTopic,
            lastAppliedPayload = appConfigPayload,
            createdPanelIds = newPanels.map { it.id },
            lastKnownDashboardOrder = deviceConfig.dashboardOrder
        )
        configRepository.applyDeviceAutoConfig(
            oldPanelIds = emptySet(),
            updatedDevice = device,
            targetGroupId = targetGroupId,
            newPanels = newPanels,
            newClusters = built.clusters
        )
        configRepository.resyncDashboardGroupOrder()
    }

    // [notificationKey] is "<brokerId>|<appConfigTopic>" (same dedup key reconcileKnownDevices
    // uses), not [deviceName] - two different pending devices legitimately can share the same
    // displayed name (a blank deviceConfig.name falls back to the topic's own last segment,
    // e.g. two bridges each republishing a "Tap_02" - a real shape this app has hit before with
    // duplicated clusters). Keying the notification id/request code off deviceName alone meant
    // the second device's "New device found" notification just silently replaced the first's,
    // even though both were correctly queued as separate pending devices in-app.
    private fun notifyNewDeviceFound(notificationKey: String, deviceName: String) {
        val channel = NotificationChannel(
            CHANNEL_ID, "New device found", NotificationManager.IMPORTANCE_DEFAULT
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val notificationId = notificationKey.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New device found")
            .setContentText("$deviceName published its own dashboard config \u2013 open Z2M Dash to add it")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and this call - safe to ignore; the
            // device still shows up as a Home screen banner.
        }
    }

    companion object {
        private const val CHANNEL_ID = "new_device_found"
    }
}
