package com.odiousapps.z2mdash.mqtt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.odiousapps.z2mdash.data.ConfigRepository
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.Panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Watches every incoming MQTT payload, across every broker/topic, for a JSON "battery" field -
 * deliberately unscoped to any configured panel, the same as SmokeAlertManager, so a device's
 * battery is monitored the moment it starts reporting one, with nothing to add on the dashboard
 * for it. Posts a notification once a reading drops into the low range (above 0, at or below
 * [LOW_BATTERY_THRESHOLD]) - 0 itself is *always* excluded, never treated as low no matter how it
 * got there: some Zigbee devices are mains/USB powered but still report a hardcoded battery of 0
 * in their firmware, which would otherwise permanently misread as "critically low". Re-notifies
 * (updating, not stacking, the same notification) as the reading keeps moving while still in the
 * low range, but only once it's moved by at least [LOW_BATTERY_STEP] since the value that
 * triggered the last notification - not on every repeated message reporting essentially the same
 * level - naming whichever dashboard cluster that topic belongs to (falling back to the topic's
 * own last segment if it isn't on the dashboard at all), and clears the notification once the
 * reading recovers back above the threshold.
 *
 * [lastNotifiedValue] only lives in memory, so it's empty again every time the app process
 * restarts. A topic's very first observed reading each run - low or not - only seeds that map
 * silently rather than being compared against it, the same treatment WateringAlertManager gives a
 * panel's first observation and for the same reason: without it, a device that's simply been
 * sitting at the same low battery for weeks would read as "just became low" on every single app
 * launch and re-notify every time, rather than only when the reading actually changes - confirmed
 * by a user report of low-battery notifications repeating on every app restart.
 */
class LowBatteryAlertManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectionManager: MqttConnectionManager
) {
    // Battery % value that triggered the last notification for each topic ("brokerId|topic") -
    // present only while that topic is currently considered low. Re-notifying requires the
    // reading to have moved (either direction - a partial recharge is as worth a fresh mention as
    // a further drop) by at least LOW_BATTERY_STEP from this value, not just any change at all.
    private val lastNotifiedValue = mutableMapOf<String, Double>()

    // Topics whose first reading this app run has already been used to seed lastNotifiedValue -
    // see the class doc's "only lives in memory" paragraph.
    private val seenThisRun = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            connectionManager.latestPayloads.collect { payloads ->
                checkBatteryLevels(payloads)
            }
        }
    }

    /** Same rationale as SmokeAlertManager.triggerTestAlert - verifies the notification for real. */
    fun triggerTestAlert() {
        notifyLowBattery(TEST_ALERT_KEY, "Test Device", isTest = true)
    }

    private fun checkBatteryLevels(payloads: Map<String, String>) {
        val config = configRepository.config.value
        payloads.forEach { (compositeKey, payload) ->
            val battery = JsonPath.extract(payload, "battery")?.toDoubleOrNull()
            val isLow = battery != null && battery > 0.0 && battery <= LOW_BATTERY_THRESHOLD

            // First observation of this topic this app run - resync silently (see class doc)
            // rather than risk reading an already-known-low battery as a fresh drop just because
            // lastNotifiedValue was reset by the restart.
            if (seenThisRun.add(compositeKey)) {
                if (isLow) lastNotifiedValue[compositeKey] = battery
                return@forEach
            }

            val wasLow = compositeKey in lastNotifiedValue
            if (isLow) {
                // battery is guaranteed non-null here (isLow's own condition required it) - the
                // compiler doesn't carry that smart-cast through from the separate isLow boolean
                // above, hence the explicit re-assertion rather than a risk of ever actually
                // hitting a null here.
                val currentValue = battery
                val movedEnoughToRenotify = wasLow &&
                    kotlin.math.abs(currentValue - lastNotifiedValue.getValue(compositeKey)) >= LOW_BATTERY_STEP
                Log.d("Z2mDash", "LowBattery: $compositeKey battery=$currentValue wasLow=$wasLow " +
                    "lastNotified=${lastNotifiedValue[compositeKey]} movedEnough=$movedEnoughToRenotify")
                if (!wasLow || movedEnoughToRenotify) {
                    lastNotifiedValue[compositeKey] = currentValue
                    // Checked here, not at the top of checkBatteryLevels, so lastNotifiedValue
                    // stays accurate even while alerts are disabled - re-enabling shouldn't
                    // re-fire for a low reading that was already active/already notified.
                    if (config.lowBatteryAlertsEnabled) {
                        notifyLowBattery(compositeKey, deviceNameFor(compositeKey), currentValue)
                    }
                }
            } else if (wasLow) {
                Log.d("Z2mDash", "LowBattery: $compositeKey cleared (battery=$battery)")
                lastNotifiedValue.remove(compositeKey)
                cancelLowBatteryNotification(compositeKey)
            }
        }
    }

    /**
     * Prefers the clusterName of a dashboard panel sharing this topic (falling back to the
     * panel's own label if it has no cluster), or the topic's own last path segment (effectively
     * the Zigbee friendly name) if no dashboard panel is configured for it at all.
     *
     * Matches with the "/app" suffix stripped from both sides before comparing, not an exact
     * string match - "battery" is reported on a device's own raw sensor topic, but that topic
     * might only appear on the dashboard as a panel's "<topic>/app" companion (e.g. an editable
     * moisture min/max threshold, whose own topic *is* the "/app" address - see
     * SensorDiscovery.buildAppConfigPayload's own doc), or vice versa after the cluster's topic
     * was changed (see ConfigRepository.retopicCluster). An exact match still wins first, so this
     * only ever widens which panel gets matched, never narrows it.
     */
    private fun deviceNameFor(compositeKey: String): String {
        val brokerId = compositeKey.substringBefore('|')
        val topic = compositeKey.substringAfter('|')
        val topicBase = topic.removeSuffix("/app")
        val panelsOnThisBroker = configRepository.config.value.groups.asSequence()
            .flatMap { it.panels }
            .filter { it.brokerId == brokerId }
        val matchingPanel = panelsOnThisBroker.firstOrNull { topicFor(it) == topic }
            ?: panelsOnThisBroker.firstOrNull { topicFor(it)?.removeSuffix("/app") == topicBase }
        val resolved = matchingPanel?.clusterName?.takeIf { it.isNotBlank() }
            ?: matchingPanel?.label?.takeIf { it.isNotBlank() }
            ?: topic.substringAfterLast("/")
        Log.d("Z2mDash", "LowBattery.deviceNameFor: topic=$topic matchedPanelTopic=${matchingPanel?.let { topicFor(it) }} " +
            "clusterName=${matchingPanel?.clusterName} label=${matchingPanel?.label} resolved=$resolved")
        return resolved
    }

    private fun topicFor(panel: Panel): String? = when (panel) {
        is Panel.Sensor -> panel.topic
        is Panel.Toggle -> panel.stateTopic.takeIf { it.isNotBlank() }
        is Panel.Button -> null
    }

    private fun notifyLowBattery(
        compositeKey: String,
        deviceName: String,
        batteryValue: Double? = null,
        isTest: Boolean = false
    ) {
        createChannelIfNeeded()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context, compositeKey.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percentText = batteryValue?.let { "${it.toInt()}%" }
        val title = if (isTest) "Test alert" else "$deviceName battery low" + (percentText?.let { " ($it)" } ?: "")
        val text = if (isTest) {
            "This is what a low battery notification looks like"
        } else if (percentText != null) {
            "Battery at $percentText – tap to open Z2M Dash"
        } else {
            "Battery is running low – tap to open Z2M Dash"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        try {
            NotificationManagerCompat.from(context).notify(compositeKey.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and this call - safe to ignore.
        }
    }

    private fun cancelLowBatteryNotification(compositeKey: String) {
        NotificationManagerCompat.from(context).cancel(compositeKey.hashCode())
    }

    private fun createChannelIfNeeded() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID, "Low battery alerts", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when a device's battery reading drops low"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "low_battery_alert"
        private const val TEST_ALERT_KEY = "test|Z2mDash low battery test"
        private const val LOW_BATTERY_THRESHOLD = 20.0
        private const val LOW_BATTERY_STEP = 5.0
    }
}
