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
 * [LOW_BATTERY_THRESHOLD]) - 0 itself is excluded since some devices report a genuine 0 before
 * they've ever sent a real reading, which would otherwise misfire as "critically low" - naming
 * whichever dashboard cluster that topic belongs to (falling back to the topic's own last
 * segment if it isn't on the dashboard at all), and clears the notification once the reading
 * recovers back above the threshold.
 */
class LowBatteryAlertManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectionManager: MqttConnectionManager
) {
    // Topics ("brokerId|topic") currently flagged low, so the alert fires once on the drop into
    // range rather than on every repeated message while the reading stays low.
    private val topicsLow = mutableSetOf<String>()

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
            val wasLow = compositeKey in topicsLow
            when {
                isLow && !wasLow -> {
                    topicsLow.add(compositeKey)
                    // Checked here, not at the top of checkBatteryLevels, so topicsLow stays
                    // accurate even while alerts are disabled - re-enabling shouldn't re-fire for
                    // a low reading that was already active.
                    if (config.lowBatteryAlertsEnabled) {
                        notifyLowBattery(compositeKey, deviceNameFor(compositeKey))
                    }
                }
                !isLow && wasLow -> {
                    topicsLow.remove(compositeKey)
                    cancelLowBatteryNotification(compositeKey)
                }
            }
        }
    }

    /**
     * Prefers the clusterName of a dashboard panel sharing this topic (falling back to the
     * panel's own label if it has no cluster), or the topic's own last path segment if no
     * dashboard panel is configured for it at all.
     */
    private fun deviceNameFor(compositeKey: String): String {
        val brokerId = compositeKey.substringBefore('|')
        val topic = compositeKey.substringAfter('|')
        val matchingPanel = configRepository.config.value.groups.asSequence()
            .flatMap { it.panels }
            .firstOrNull { it.brokerId == brokerId && topicFor(it) == topic }
        return matchingPanel?.clusterName?.takeIf { it.isNotBlank() }
            ?: matchingPanel?.label?.takeIf { it.isNotBlank() }
            ?: topic.substringAfterLast("/")
    }

    private fun topicFor(panel: Panel): String? = when (panel) {
        is Panel.Sensor -> panel.topic
        is Panel.Toggle -> panel.stateTopic.takeIf { it.isNotBlank() }
        is Panel.Button -> null
    }

    private fun notifyLowBattery(compositeKey: String, deviceName: String, isTest: Boolean = false) {
        createChannelIfNeeded()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context, compositeKey.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isTest) "Test alert" else "$deviceName battery low"
        val text = if (isTest) {
            "This is what a low battery notification looks like"
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
    }
}
