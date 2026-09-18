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

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this, appScope)
        val payloadCacheRepository = PayloadCacheRepository(this)
        connectionManager = MqttConnectionManager(appScope, payloadCacheRepository)
        deviceAutoConfigManager = DeviceAutoConfigManager(this, configRepository, connectionManager)
        smokeAlertManager = SmokeAlertManager(this, configRepository, connectionManager)

        connectionManager.applyConfig(configRepository.config.value)
        connectionManager.startPersistingCache()
        deviceAutoConfigManager.start(appScope)
        smokeAlertManager.start(appScope)
        appScope.launch {
            configRepository.config.collect { config ->
                connectionManager.applyConfig(config)
            }
        }

        // Without this, MQTT connections (established just above) run for as long as the process
        // happens to survive, regardless of the "Background Work" setting or whether the user has
        // actually backed all the way out of the app - MainActivity finishing doesn't stop this
        // Application-scoped singleton or its coroutines. ProcessLifecycleOwner (rather than
        // MainActivity's own onStop/onDestroy) specifically reports the app as a whole being
        // backgrounded/foregrounded, not individual Activity transitions - e.g. it doesn't fire on
        // a configuration change that recreates the Activity, which a plain Activity callback would.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Only disconnect when the user has explicitly said they don't want the broker
                // connection kept alive in the background - otherwise this would defeat that
                // setting's own purpose the moment the app left the foreground for any reason.
                if (!configRepository.config.value.backgroundWorkEnabled) {
                    connectionManager.disconnectAll()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                // Reconnects anything disconnectAll() above just tore down, but also covers the
                // backgroundWorkEnabled=true case: Android can still freeze/kill the process (or
                // just silently drop the socket under Doze) even with the foreground service
                // running, and nothing else in the app was otherwise re-checking connection health
                // on resume - without this, tiles could keep showing payloads from days ago after
                // reopening, having no way to tell the difference between "genuinely no new data"
                // and "the connection quietly died while backgrounded." Safe to call unconditionally:
                // MqttConnection.connect() is a no-op for an already-connected broker, and
                // applyConfig() always re-issues every subscribe() call regardless.
                connectionManager.applyConfig(configRepository.config.value)
            }
        })
    }
}
