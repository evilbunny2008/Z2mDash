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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

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

    private fun reconcileKnownDevices(payloads: Map<String, String>) {
        val config = configRepository.config.value
        config.autoConfiguredDevices.forEach { device ->
            val currentPayload = payloads["${device.brokerId}|${device.appConfigTopic}"] ?: return@forEach
            if (currentPayload == device.lastAppliedPayload) return@forEach

            val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return@forEach
            val sensorPayload = payloads["${device.brokerId}|${device.sensorTopic}"]
            val sensorFieldKeys = sensorPayload?.let { SensorDiscovery.fieldKeysOf(it) } ?: emptySet()

            val builtPanels = SensorDiscovery.buildPanels(
                brokerId = device.brokerId,
                sensorTopic = device.sensorTopic,
                sensorFieldKeys = sensorFieldKeys,
                appConfigTopic = device.appConfigTopic,
                appConfigPayload = currentPayload,
                deviceConfig = deviceConfig
            )
            if (builtPanels.isEmpty()) return@forEach

            val existingPanelsList = config.groups.asSequence()
                .flatMap { it.panels }
                .filter { it.id in device.createdPanelIds }
                .toList()
            val existingKeys = identityKeys(existingPanelsList)
            val existingPanels = existingPanelsList.associateBy { existingKeys.getValue(it) }
            val builtKeys = identityKeys(builtPanels)
            // Only adopt the payload's order for an existing panel if its order_version is
            // strictly newer than what this phone last applied for the device - otherwise a
            // stale/retained redelivery (reconnect, or another phone not yet caught up) would
            // silently undo the user's local reorder. The id is always carried over via
            // identity match (not buildPanels fresh random id) so Compose keeps panel state.
            // A brand-new panel always takes its order from the payload.
            val incomingOrderVersion = deviceConfig.orderVersion
            val adoptIncomingOrder = incomingOrderVersion != null && incomingOrderVersion > device.lastKnownOrderVersion
            val newPanels = builtPanels.map { panel ->
                val existing = existingPanels[builtKeys.getValue(panel)] ?: return@map panel
                val displayOrder = if (adoptIncomingOrder) panel.displayOrder else existing.displayOrder
                when (panel) {
                    // editable is a local-only UI preference (see Panel.Sensor's doc) with no
                    // representation in the device's own payload - preserved the same way
                    // displayOrder is, so a rebuilt panel doesn't silently lose it.
                    is Panel.Sensor -> panel.copy(
                        id = existing.id,
                        displayOrder = displayOrder,
                        editable = (existing as? Panel.Sensor)?.editable ?: false
                    )
                    is Panel.Toggle -> panel.copy(id = existing.id, displayOrder = displayOrder)
                    is Panel.Button -> panel.copy(id = existing.id, displayOrder = displayOrder)
                }
            }

            val targetGroupId = resolveTargetGroupId(deviceConfig.group, device) ?: return@forEach

            val updatedDevice = device.copy(
                lastAppliedPayload = currentPayload,
                createdPanelIds = newPanels.map { it.id },
                lastKnownOrderVersion = if (adoptIncomingOrder) incomingOrderVersion else device.lastKnownOrderVersion
            )
            configRepository.applyDeviceAutoConfig(
                oldPanelIds = device.createdPanelIds.toSet(),
                updatedDevice = updatedDevice,
                targetGroupId = targetGroupId,
                newPanels = newPanels
            )
            // Same last-writer-wins gate as panel/cluster order above: only reposition the group
            // itself when this payload's order_version is genuinely newer, so a stale retained
            // redelivery can't undo a more recent drag-reorder of the dashboard groups.
            if (adoptIncomingOrder) {
                deviceConfig.dashboardOrder?.let { order ->
                    configRepository.moveGroupToIndex(targetGroupId, order)
                }
            }
        }
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
            notifyNewDeviceFound(deviceName)
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

        val newPanels = SensorDiscovery.buildPanels(
            brokerId = brokerId,
            sensorTopic = sensorTopic,
            sensorFieldKeys = sensorFieldKeys,
            appConfigTopic = appConfigTopic,
            appConfigPayload = appConfigPayload,
            deviceConfig = deviceConfig
        )
        if (newPanels.isEmpty()) return

        val config = configRepository.config.value
        val targetGroupId = deviceConfig.group?.let { name ->
            config.groups.find { it.name.equals(name, ignoreCase = true) }?.id
                ?: UUID.randomUUID().toString().also { id ->
                    configRepository.upsertGroup(PanelGroup(id = id, name = name))
                }
        } ?: config.groups.firstOrNull()?.id
            ?: UUID.randomUUID().toString().also { id ->
                configRepository.upsertGroup(PanelGroup(id = id, name = "Discovered Sensors"))
            }

        val device = AutoConfiguredDevice(
            brokerId = brokerId,
            sensorTopic = sensorTopic,
            appConfigTopic = appConfigTopic,
            lastAppliedPayload = appConfigPayload,
            createdPanelIds = newPanels.map { it.id }
        )
        configRepository.applyDeviceAutoConfig(
            oldPanelIds = emptySet(),
            updatedDevice = device,
            targetGroupId = targetGroupId,
            newPanels = newPanels
        )
    }

    private fun resolveTargetGroupId(declaredGroupName: String?, device: AutoConfiguredDevice): String? {
        val config = configRepository.config.value
        if (!declaredGroupName.isNullOrBlank()) {
            val existing = config.groups.find { it.name.equals(declaredGroupName, ignoreCase = true) }
            if (existing != null) return existing.id
            val id = UUID.randomUUID().toString()
            configRepository.upsertGroup(PanelGroup(id = id, name = declaredGroupName))
            return id
        }
        val ownedPanelIds = device.createdPanelIds.toSet()
        return config.groups.find { g -> g.panels.any { it.id in ownedPanelIds } }?.id
    }

    private fun notifyNewDeviceFound(deviceName: String) {
        val channel = NotificationChannel(
            CHANNEL_ID, "New device found", NotificationManager.IMPORTANCE_DEFAULT
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context, deviceName.hashCode(), launchIntent,
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
            NotificationManagerCompat.from(context).notify(deviceName.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and this call - safe to ignore; the
            // device still shows up as a Home screen banner.
        }
    }

    companion object {
        private const val CHANNEL_ID = "new_device_found"
    }
}
