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
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.data.ConfigRepository
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.TemperatureAlertLimits
import com.odiousapps.z2mdash.data.TileIcon
import com.odiousapps.z2mdash.data.clusterNameOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Watches every temperature sensor with its own limits set (AppConfig.temperatureAlertLimits,
 * set by tapping the tile's reading) and notifies when the reading rises to its high limit or
 * drops to its low limit - e.g. a sensor sitting in a window, so the window can be shut before the
 * room heats up on a hot day, or overnight once it gets too cold. The two sides are tracked
 * entirely independently, so the low limit may sit above the high one (rising to 23 and dropping
 * to 26 on a weather station is a normal setup) without either interfering with the other. Each
 * side fires once on the
 * crossing, then re-arms only after the reading has come back by at least [HYSTERESIS] (clearing
 * that notification at the same time), so a reading hovering right on the limit doesn't
 * re-notify on every small wobble. Even once re-armed, a side stays quiet for [COOLDOWN_MILLIS]
 * after it last notified: a 0.5° margin alone still let an afternoon hovering around the limit
 * notify roughly hourly, when the point is one heads-up per hot day or cold night.
 *
 * Follows WateringAlertManager's lead on everything that made that one fire spuriously: readings
 * are ignored until the panel's own broker is actually connected (the payloads map is pre-seeded
 * from the on-disk cache before any connection starts), and a panel's first observation each app
 * run - or its first after its limits are edited - only silently resyncs which side of each limit
 * it's on rather than counting as a crossing. Without the latter, a window already past its limit
 * would re-notify on every app restart, and saving a new limit the reading already sits beyond
 * would fire the instant the dialog closed, while the user is looking right at it.
 */
class TemperatureAlertManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectionManager: MqttConnectionManager
) {
    // Panel ids currently at/beyond their high or low limit - present from the crossing until the
    // reading has come back by HYSTERESIS, so each limit notifies once per excursion.
    private val panelsAboveHigh = mutableSetOf<String>()
    private val panelsBelowLow = mutableSetOf<String>()

    // The limits last used to classify each panel - absent for a panel never yet classified this
    // run. Doubles as the first-observation marker, the same way WateringAlertManager.lastMidpoint
    // does (see the class doc).
    private val lastLimits = mutableMapOf<String, TemperatureAlertLimits>()

    // When each notification key (see highKey/lowKey) last actually notified - see COOLDOWN_MILLIS.
    private val lastNotifiedAt = mutableMapOf<String, Long>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            // Config too, not just payloads, so editing or clearing a limit resyncs (and clears a
            // now-stale notification) straight away rather than on that sensor's next message.
            combine(connectionManager.latestPayloads, configRepository.config) { payloads, _ -> payloads }
                .collect { payloads -> checkTemperaturePanels(payloads) }
        }
    }

    /** Same rationale as SmokeAlertManager.triggerTestAlert - verifies the notification for real. */
    fun triggerTestAlert() {
        notifyTemperature(TEST_ALERT_KEY, title = context.getString(R.string.notif_test_title),
            text = context.getString(R.string.temperature_notif_test_text))
    }

    private fun checkTemperaturePanels(payloads: Map<String, String>) {
        val config = configRepository.config.value
        val panels = config.groups.asSequence()
            .flatMap { it.panels }
            .filterIsInstance<Panel.Sensor>()
            .filter { isTemperatureAlertPanel(it) }
            .toList()

        // Limits cleared, or the panel itself deleted - forget it and take down anything it left
        // showing, rather than leaving a "too warm" notification up for a limit that's gone.
        val activeIds = panels.filter { config.temperatureAlertLimits[it.id] != null }.map { it.id }.toSet()
        (lastLimits.keys - activeIds).forEach { panelId ->
            lastLimits.remove(panelId)
            panelsAboveHigh.remove(panelId)
            panelsBelowLow.remove(panelId)
            cancelNotification(highKey(panelId))
            cancelNotification(lowKey(panelId))
        }

        val connectionStates = connectionManager.connectionStates.value
        panels.forEach { panel ->
            val limits = config.temperatureAlertLimits[panel.id] ?: return@forEach
            if (connectionStates[panel.brokerId] != ConnectionState.CONNECTED) return@forEach
            val raw = payloads["${panel.brokerId}|${panel.topic}"] ?: return@forEach
            val currentValue = JsonPath.extract(raw, panel.jsonPath)?.toDoubleOrNull() ?: return@forEach
            val high = limits.high
            val low = limits.low

            if (lastLimits[panel.id] != limits) {
                // Fresh limits get a fresh cooldown - the user is asking to be told about these.
                if (lastLimits[panel.id] != null) {
                    lastNotifiedAt.remove(highKey(panel.id))
                    lastNotifiedAt.remove(lowKey(panel.id))
                }
                lastLimits[panel.id] = limits
                resync(panel.id, high != null && currentValue >= high, panelsAboveHigh, highKey(panel.id))
                resync(panel.id, low != null && currentValue <= low, panelsBelowLow, lowKey(panel.id))
                return@forEach
            }

            val name = config.clusterNameOf(panel).ifBlank { panel.label }
            if (high != null) {
                if (panel.id !in panelsAboveHigh && currentValue >= high) {
                    panelsAboveHigh.add(panel.id)
                    if (config.temperatureAlertsEnabled && isOffCooldown(highKey(panel.id))) {
                        notifyTemperature(
                            highKey(panel.id),
                            title = context.getString(R.string.temperature_notif_high_title, name, format(panel, currentValue)),
                            text = context.getString(R.string.temperature_notif_high_text, format(panel, high), context.getString(R.string.app_name))
                        )
                    }
                } else if (panel.id in panelsAboveHigh && currentValue <= high - HYSTERESIS) {
                    panelsAboveHigh.remove(panel.id)
                    cancelNotification(highKey(panel.id))
                }
            }
            if (low != null) {
                if (panel.id !in panelsBelowLow && currentValue <= low) {
                    panelsBelowLow.add(panel.id)
                    if (config.temperatureAlertsEnabled && isOffCooldown(lowKey(panel.id))) {
                        notifyTemperature(
                            lowKey(panel.id),
                            title = context.getString(R.string.temperature_notif_low_title, name, format(panel, currentValue)),
                            text = context.getString(R.string.temperature_notif_low_text, format(panel, low), context.getString(R.string.app_name))
                        )
                    }
                } else if (panel.id in panelsBelowLow && currentValue >= low + HYSTERESIS) {
                    panelsBelowLow.remove(panel.id)
                    cancelNotification(lowKey(panel.id))
                }
            }
        }
    }

    /** Whether [key] may notify again, recording now as its latest notification if so. */
    private fun isOffCooldown(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastNotifiedAt[key] ?: 0L) < COOLDOWN_MILLIS) return false
        lastNotifiedAt[key] = now
        return true
    }

    /** Silently records which side of one limit [panelId] is on - see the class doc's resync paragraph. */
    private fun resync(panelId: String, beyond: Boolean, tripped: MutableSet<String>, key: String) {
        if (beyond) {
            tripped.add(panelId)
        } else {
            tripped.remove(panelId)
            cancelNotification(key)
        }
    }

    private fun format(panel: Panel.Sensor, value: Double): String =
        "%.${panel.decimals}f".format(value) + panel.unit

    private fun highKey(panelId: String) = "$panelId|temperature_high"
    private fun lowKey(panelId: String) = "$panelId|temperature_low"

    private fun notifyTemperature(key: String, title: String, text: String) {
        createChannelIfNeeded()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context, key.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and this call - safe to ignore.
        }
    }

    private fun cancelNotification(key: String) {
        NotificationManagerCompat.from(context).cancel(key.hashCode())
    }

    private fun createChannelIfNeeded() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // The default notification sound rather than WateringAlertManager's alarm stream - worth
        // hearing, but it should still respect silent/Do Not Disturb overnight like any other
        // notification, leaving that choice to the phone's own settings.
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.temperature_channel), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.temperature_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "temperature_alert"
        private const val TEST_ALERT_KEY = "test|Z2mDash temperature test alert"
        // In the sensor's own units (normally °C).
        private const val HYSTERESIS = 1.0
        // Long enough to cover a whole hot afternoon or cold night with a single notification.
        private const val COOLDOWN_MILLIS = 8 * 60 * 60 * 1000L

        /**
         * Whether tapping [panel]'s reading opens the temperature limits dialog - a live
         * temperature reading only, never an editable preference tile (whose tap already edits
         * its own value).
         */
        fun isTemperatureAlertPanel(panel: Panel.Sensor): Boolean =
            panel.icon == TileIcon.TEMPERATURE && !panel.editable && panel.topic.isNotBlank()
    }
}
