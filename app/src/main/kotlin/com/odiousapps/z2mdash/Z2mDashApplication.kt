package com.odiousapps.z2mdash

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.odiousapps.z2mdash.data.ConfigRepository
import com.odiousapps.z2mdash.data.PayloadCacheRepository
import com.odiousapps.z2mdash.mqtt.DeviceAutoConfigManager
import com.odiousapps.z2mdash.mqtt.MqttConnectionManager
import com.odiousapps.z2mdash.mqtt.SmokeAlertManager
import com.odiousapps.z2mdash.mqtt.WateringAlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Z2mDashApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    lateinit var configRepository: ConfigRepository
        private set
    lateinit var connectionManager: MqttConnectionManager
        private set
    lateinit var deviceAutoConfigManager: DeviceAutoConfigManager
        private set
    lateinit var smokeAlertManager: SmokeAlertManager
        private set
    lateinit var wateringAlertManager: WateringAlertManager
        private set

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this, appScope)
        val payloadCacheRepository = PayloadCacheRepository(this)
        connectionManager = MqttConnectionManager(appScope, payloadCacheRepository)
        deviceAutoConfigManager = DeviceAutoConfigManager(this, configRepository, connectionManager)
        smokeAlertManager = SmokeAlertManager(this, configRepository, connectionManager)
        wateringAlertManager = WateringAlertManager(this, configRepository, connectionManager)

        connectionManager.applyConfig(configRepository.config.value)
        connectionManager.startPersistingCache()
        deviceAutoConfigManager.start(appScope)
        smokeAlertManager.start(appScope)
        wateringAlertManager.start(appScope)
        appScope.launch {
            configRepository.config.collect { config ->
                connectionManager.applyConfig(config)
            }
        }

        // MainActivity finishing doesn't stop this Application-scoped singleton, so without an
        // explicit lifecycle hook MQTT would keep running regardless of the Background Work
        // setting. ProcessLifecycleOwner reports the app as a whole backgrounding/foregrounding
        // (unlike an Activity callback, it ignores config-change recreations).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Only disconnect if the user hasn't opted to keep the broker alive in the background.
                if (!configRepository.config.value.backgroundWorkEnabled) {
                    connectionManager.disconnectAll()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                // Reconnects what onStop tore down, and also re-heals backgroundWorkEnabled=true
                // sessions where Android silently dropped the socket (e.g. under Doze) without
                // killing the process. Safe to call unconditionally: connect() and applyConfig()'s
                // subscribe() calls are no-ops/idempotent when already connected/subscribed.
                connectionManager.applyConfig(configRepository.config.value)
            }
        })
    }
}
