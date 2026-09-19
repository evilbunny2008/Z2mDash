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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Watches every incoming MQTT payload, across every broker/topic, for a JSON "smoke": true
 * field - deliberately unscoped to any configured device/panel, so a smoke detector is
 * monitored the moment it starts publishing, with no registration needed. Posts a
 * high-priority notification on the true transition, and clears it once smoke clears.
 */
class SmokeAlertManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectionManager: MqttConnectionManager
) {
    // Topics currently in an active alarm state, so we alert once on the transition to
    // true rather than on every repeated message while an alarm stays ongoing.
    private val topicsInAlarm = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            connectionManager.latestPayloads.collect { payloads ->
                checkForSmoke(payloads)
            }
        }
    }

    /**
     * Fires a real notification via the same logic as a genuine alert, so the "test" button
     * verifies the notification/sound actually work. Uses a dedicated key, not a real topic,
     * so it never touches topicsInAlarm tracking.
     */
    fun triggerTestAlert() {
        val playSound = configRepository.config.value.smokeAlertSoundEnabled
        notifySmokeDetected(TEST_ALERT_KEY, playSound, isTest = true)
    }

    private fun checkForSmoke(payloads: Map<String, String>) {
        val config = configRepository.config.value

        payloads.forEach { (compositeKey, payload) ->
            val smokeDetected = isSmokeDetected(payload)
            val wasInAlarm = compositeKey in topicsInAlarm
            when {
                smokeDetected && !wasInAlarm -> {
                    topicsInAlarm.add(compositeKey)
                    // Checked here, not at the top of checkForSmoke, so topicsInAlarm stays
                    // accurate even while alerts are disabled - re-enabling shouldn't re-fire
                    // for smoke that was already active.
                    if (config.smokeAlertsEnabled) {
                        notifySmokeDetected(compositeKey, config.smokeAlertSoundEnabled)
                    }
                }
                !smokeDetected && wasInAlarm -> {
                    topicsInAlarm.remove(compositeKey)
                    cancelSmokeNotification(compositeKey)
                }
            }
        }
    }

    private fun isSmokeDetected(payload: String): Boolean {
        return try {
            val obj = Json.parseToJsonElement(payload) as? JsonObject ?: return false
            val smokeField = obj["smoke"] as? JsonPrimitive ?: return false
            smokeField.booleanOrNull == true || smokeField.contentOrNull.equals("true", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun notifySmokeDetected(compositeKey: String, playSound: Boolean, isTest: Boolean = false) {
        val topic = compositeKey.substringAfter('|')
        val deviceName = topic.substringAfterLast("/")

        createChannelsIfNeeded()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context, compositeKey.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // A channel's sound can't be changed once created, so we pre-create a sound and a
        // silent channel and pick between them at send time to make the toggle work.
        val channelId = if (playSound) CHANNEL_ID_SOUND else CHANNEL_ID_SILENT
        val title = if (isTest) "Test alert" else "Smoke detected!"
        val text = if (isTest) {
            "This is what a smoke alert notification looks like"
        } else {
            "$deviceName reported smoke \u2013 tap to open Z2M Dash"
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
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

    private fun cancelSmokeNotification(compositeKey: String) {
        NotificationManagerCompat.from(context).cancel(compositeKey.hashCode())
    }

    private fun createChannelsIfNeeded() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // A custom sound (res/raw/smoke_alarm.wav) rather than the device's default alarm
        // ringtone, so this alert is distinguishable from any other alarm.
        val alarmSoundUri = "android.resource://${context.packageName}/${R.raw.smoke_alarm}".toUri()

        val soundChannel = NotificationChannel(
            CHANNEL_ID_SOUND, "Smoke alerts (with sound)", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a device reports smoke detected, with an alarm-style sound"
            enableVibration(true)
            setSound(alarmSoundUri, alarmAttributes)
        }
        val silentChannel = NotificationChannel(
            CHANNEL_ID_SILENT, "Smoke alerts (silent)", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a device reports smoke detected, without sound"
            enableVibration(true)
            setSound(null, null)
        }

        manager.createNotificationChannel(soundChannel)
        manager.createNotificationChannel(silentChannel)
    }

    companion object {
        private const val CHANNEL_ID_SOUND = "smoke_alert_sound"
        private const val CHANNEL_ID_SILENT = "smoke_alert_silent"
        private const val TEST_ALERT_KEY = "test|Z2mDash test alert"
    }
}
