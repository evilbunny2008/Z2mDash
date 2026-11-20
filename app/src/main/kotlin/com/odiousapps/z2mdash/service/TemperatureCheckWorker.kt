package com.odiousapps.z2mdash.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.AppConfig
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.mqtt.TemperatureAlertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * The battery-friendly alternative to MqttForegroundService for temperature alerts: while
 * "Background Work" is off, connects roughly every 15 minutes (Doze can stretch that to hours
 * overnight), waits for each watched sensor's retained reading, lets TemperatureAlertManager
 * evaluate it as it would a live one, then disconnects again. Only works for sensors Zigbee2MQTT
 * retains - a sensor that isn't sends nothing on subscribe, so a check never sees it (see
 * MqttConnectionManager.watchRetain, which flags those to the user).
 */
class TemperatureCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as Z2mDashApplication
        app.configRepository.isLoaded.first { it }
        val config = app.configRepository.config.value
        // Open in the foreground, the app's own live connection is already watching.
        if (!shouldSchedule(config) || withContext(Dispatchers.Main) { isInForeground() }) return Result.success()

        val keys = watchedKeys(config)
        // Usually already done by Z2mDashApplication.onCreate, as checks normally run in a freshly
        // started process - but a cached one was disconnected when the app was last backgrounded.
        withContext(Dispatchers.Main) { app.connectionManager.applyConfig(config) }
        withTimeoutOrNull(READING_TIMEOUT) {
            app.connectionManager.latestPayloadTimestamps.first {
                keys.all { app.connectionManager.isFromCurrentConnection(it) }
            }
        }
        // Covers the alert manager's evaluation of the batch it was just handed, and the payload
        // cache's 2s-debounced write.
        delay(SETTLE_DELAY)
        withContext(Dispatchers.Main) {
            if (!isInForeground() && !app.configRepository.config.value.backgroundWorkEnabled) {
                app.connectionManager.disconnectAll()
            }
        }
        return Result.success()
    }

    private fun isInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        private const val WORK_NAME = "temperature_check"
        private val READING_TIMEOUT = 30.seconds
        private val SETTLE_DELAY = 3.seconds

        fun shouldSchedule(config: AppConfig): Boolean =
            config.periodicChecksEnabled && !config.backgroundWorkEnabled &&
                config.temperatureAlertsEnabled && config.temperatureAlertLimits.isNotEmpty()

        /** "brokerId|topic" of every temperature tile with limits set on an auto-connect broker. */
        private fun watchedKeys(config: AppConfig): Set<String> {
            val autoConnectIds = config.brokers.filter { it.autoConnect }.map { it.id }.toSet()
            return config.groups.asSequence()
                .flatMap { it.panels }
                .filterIsInstance<Panel.Sensor>()
                .filter { TemperatureAlertManager.isTemperatureAlertPanel(it) }
                .filter { config.temperatureAlertLimits[it.id] != null && it.brokerId in autoConnectIds }
                .map { "${it.brokerId}|${it.topic}" }
                .toSet()
        }

        /** Schedules or cancels the periodic check to match [config]. */
        fun sync(context: Context, config: AppConfig) {
            val workManager = WorkManager.getInstance(context)
            if (shouldSchedule(config)) {
                val request = PeriodicWorkRequestBuilder<TemperatureCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                workManager.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
