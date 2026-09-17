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
 * Keeps every device that was configured via its own "<topic>/app" payload
 * (see SensorDiscovery/DiscoverScreen) up to date - whenever that topic's
 * retained payload changes, this regenerates the device's panels to match,
 * live, without needing go to Discover screen.
 *
 * Also watches every broker's full topic stream (MqttConnectionManager now
 * subscribes every broker to "#" continuously) for brand-new "<topic>/app"
 * topics it hasn't seen before, adds them to a pending list, and notifies the
 * user.
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

            val existingPanels = config.groups.asSequence()
                .flatMap { it.panels }
                .filter { it.id in device.createdPanelIds }
                .associateBy(::identityKey)
            // This payload's group_order/panel_order is only trusted for a panel
            // that already exists here when it's stamped with a strictly newer
            // order_version than the last one this phone adopted for this
            // device - i.e. a genuine, more recent reorder (from this phone or
            // another one sharing the same broker). Otherwise - no order_version
            // at all (a payload no app has ever written an order to), or one no
            // newer than what's already applied (a stale retained redelivery,
            // e.g. from a reconnect, or another phone that hasn't caught up yet)
            // - the existing displayOrder is kept as-is. Without this, a stale
            // echo would silently undo the user's local reorder the next time
            // this device's payload happens to be reconciled. Either way the id
            // is always carried over (matched by stable field/command identity,
            // not the fresh random id buildPanels just assigned it) so Compose
            // keeps its remembered state for the panel. Only a genuinely new
            // panel (no existing match) takes its order from the payload
            // unconditionally, same as before.
            val incomingOrderVersion = deviceConfig.orderVersion
            val adoptIncomingOrder = incomingOrderVersion != null && incomingOrderVersion > device.lastKnownOrderVersion
            val newPanels = builtPanels.map { panel ->
                val existing = existingPanels[identityKey(panel)] ?: return@map panel
                val displayOrder = if (adoptIncomingOrder) panel.displayOrder else existing.displayOrder
                when (panel) {
                    is Panel.Sensor -> panel.copy(id = existing.id, displayOrder = displayOrder)
                    is Panel.Toggle -> panel.copy(id = existing.id, displayOrder = displayOrder)
                    is Panel.Button -> panel.copy(id = existing.id, displayOrder = displayOrder)
                }
            }

            val targetGroupId = resolveTargetGroupId(deviceConfig.group, device) ?: return@forEach

            val updatedDevice = device.copy(
                lastAppliedPayload = currentPayload,
                createdPanelIds = newPanels.map { it.id },
                lastKnownOrderVersion = if (adoptIncomingOrder) incomingOrderVersion!! else device.lastKnownOrderVersion
            )
            configRepository.applyDeviceAutoConfig(
                oldPanelIds = device.createdPanelIds.toSet(),
                updatedDevice = updatedDevice,
                targetGroupId = targetGroupId,
                newPanels = newPanels
            )
        }
    }

    /**
     * A stable identity for a panel, independent of its random UUID - so a
     * freshly rebuilt panel (buildPanels always mints a new id) can still be
     * recognised as "the same panel" as one already in the config. Sensors are
     * identified by which field of the device they render; controls by the
     * command they send - both fixed by the device's own config, unlike id.
     */
    private fun identityKey(panel: Panel): String = when (panel) {
        is Panel.Sensor -> "sensor|${panel.topic}|${panel.jsonPath}"
        is Panel.Toggle -> "toggle|${panel.commandTopic}"
        is Panel.Button -> "button|${panel.commandTopic}"
    }

    /** Scans every topic ending in "/app" for ones not yet tracked, pending, or dismissed. */
    private fun detectNewDevices(payloads: Map<String, String>) {
        val config = configRepository.config.value
        val trackedKeys = config.autoConfiguredDevices.map { "${it.brokerId}|${it.appConfigTopic}" }.toSet()
        val pendingKeys = config.pendingAutoConfigDevices.map { "${it.brokerId}|${it.appConfigTopic}" }.toSet()
        val ignoredKeys = config.ignoredAppConfigTopics.toSet()
        val brokerIds = config.brokers.map { it.id }.toSet()

        payloads.forEach { (compositeKey, payload) ->
            val separatorIndex = compositeKey.indexOf('|')
            if (separatorIndex < 0) return@forEach
            val brokerId = compositeKey.substring(0, separatorIndex)
            val topic = compositeKey.substring(separatorIndex + 1)
            if (brokerId !in brokerIds || !topic.endsWith("/app")) return@forEach

            val key = "$brokerId|$topic"
            if (key in trackedKeys || key in pendingKeys || key in ignoredKeys) return@forEach

            val deviceConfig = SensorDiscovery.parseDeviceAppConfig(payload) ?: return@forEach
            val sensorTopic = topic.removeSuffix("/app")
            val deviceName = deviceConfig.name.ifBlank { sensorTopic.substringAfterLast("/") }

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
            // Permission revoked between the check above and this call - safe to ignore,
            // the pending device still shows up as a Home screen banner regardless.
        }
    }

    companion object {
        private const val CHANNEL_ID = "new_device_found"
    }
}
