package com.odiousapps.z2mdash.mqtt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.data.ConfigRepository
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.TileIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Watches every configured moisture sensor (a Panel.Sensor with icon MOISTURE and an ideal range
 * configured) for its reading rising up through the middle of that ideal range, and vibrates plus
 * plays a short chime when it does - so watering a plant doesn't require staring at the screen
 * the whole time. Deliberately keyed on the *rising* crossing, not just being above the midpoint:
 * without that, a value that was already above the midpoint the first time it's ever observed
 * (e.g. right after app launch) would alert immediately with no watering having happened at all.
 * Also requires the rise to be a genuine one - at least [RISE_THRESHOLD] above the lowest reading
 * seen since the panel was last below the midpoint - rather than any increase at all, since
 * comparing only two adjacent MQTT messages would trigger on ordinary sensor noise (a reading
 * that's merely stopped falling, or wobbled up a fraction of a percent) as readily as on someone
 * actually watering.
 */
class WateringAlertManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectionManager: MqttConnectionManager
) {
    // Lowest reading seen for each panel during its *current* streak below the ideal range's
    // midpoint - the baseline a rise is measured from. Reset to the current value the moment a
    // panel drops back below the midpoint (a fresh trough to measure the next rise from), then
    // kept tracking the true minimum for as long as it stays below.
    private val baselineLow = mutableMapOf<String, Double>()

    // Panel ids currently at-or-above their ideal range's midpoint, so the alert fires once on
    // the crossing rather than on every subsequent message while it stays up there.
    private val panelsAboveMidpoint = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            connectionManager.latestPayloads.collect { payloads ->
                checkMoisturePanels(payloads)
            }
        }
    }

    /** Same rationale as SmokeAlertManager.triggerTestAlert - verifies sound/vibration for real. */
    fun triggerTestAlert() {
        notifyMoistureTarget("Test Sensor", TEST_ALERT_KEY, isTest = true)
    }

    private fun checkMoisturePanels(payloads: Map<String, String>) {
        val config = configRepository.config.value
        val moisturePanels = config.groups.asSequence()
            .flatMap { it.panels }
            .filterIsInstance<Panel.Sensor>()
            .filter { it.icon == TileIcon.MOISTURE && it.idealRangeTopic.isNotBlank() }

        moisturePanels.forEach { panel ->
            val raw = payloads["${panel.brokerId}|${panel.topic}"] ?: return@forEach
            val currentValue = JsonPath.extract(raw, panel.jsonPath)?.toDoubleOrNull() ?: return@forEach
            val idealRaw = payloads["${panel.brokerId}|${panel.idealRangeTopic}"]
            val min = idealRaw?.let { JsonPath.extract(it, panel.idealMinPath) }?.toDoubleOrNull()
            val max = idealRaw?.let { JsonPath.extract(it, panel.idealMaxPath) }?.toDoubleOrNull()
            if (min == null || max == null) return@forEach
            val midpoint = (min + max) / 2.0
            val wasAbove = panel.id in panelsAboveMidpoint
            val isAbove = currentValue >= midpoint

            if (!isAbove) {
                if (wasAbove) {
                    // Just dropped back below the midpoint - start tracking a fresh trough for
                    // the next rise, rather than reusing a stale low left over from whatever
                    // streak came before the last alert (which could let a small wobble near the
                    // midpoint re-trigger immediately, measured against a long-irrelevant low).
                    baselineLow[panel.id] = currentValue
                } else {
                    val currentLow = baselineLow[panel.id]
                    if (currentLow == null || currentValue < currentLow) baselineLow[panel.id] = currentValue
                }
            }
            val risenEnough = baselineLow[panel.id]?.let { low -> currentValue - low >= RISE_THRESHOLD } == true

            when {
                isAbove && !wasAbove && risenEnough -> {
                    panelsAboveMidpoint.add(panel.id)
                    if (config.wateringAlertsEnabled) {
                        notifyMoistureTarget(panel.label, panel.id)
                    }
                }
                !isAbove && wasAbove -> {
                    panelsAboveMidpoint.remove(panel.id)
                    cancelMoistureNotification(panel.id)
                }
            }
        }
    }

    private fun notifyMoistureTarget(label: String, key: String, isTest: Boolean = false) {
        createChannelIfNeeded()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context, key.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isTest) "Test alert" else "$label reached target moisture"
        val text = if (isTest) {
            "This is what a watering alert notification looks like"
        } else {
            "Rising through the middle of its ideal range – tap to open Z2M Dash"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
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

    private fun cancelMoistureNotification(key: String) {
        NotificationManagerCompat.from(context).cancel(key.hashCode())
    }

    private fun createChannelIfNeeded() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val chimeAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // A short, deliberately unobtrusive custom chime (res/raw/watering_alert.mp3) - this is
        // meant to be noticed while pottering around outside, not to demand attention the way the
        // smoke alarm sound does.
        val chimeUri = "android.resource://${context.packageName}/${R.raw.watering_alert}".toUri()

        val channel = NotificationChannel(
            CHANNEL_ID, "Watering alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Vibrates and chimes when a moisture sensor reaches the middle of its ideal range while rising"
            enableVibration(true)
            setSound(chimeUri, chimeAttributes)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "watering_alert"
        private const val TEST_ALERT_KEY = "test|Z2mDash watering test alert"
        // In the sensor's own units (moisture is normally 0-100%, so this reads as "5%").
        private const val RISE_THRESHOLD = 5.0
    }
}
