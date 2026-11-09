package com.odiousapps.z2mdash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.odiousapps.z2mdash.service.MqttForegroundService
import com.odiousapps.z2mdash.ui.navigation.AppNavHost
import com.odiousapps.z2mdash.ui.theme.Z2mDashTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Every connect attempt made before the grant failed, so reconnect now rather than leaving
    // brokers to HiveMQ's backoff (up to 30s).
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) (application as Z2mDashApplication).connectionManager.reconnectAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as Z2mDashApplication
        // Config loads off the main thread, so config.value may still be the empty default here;
        // wait for isLoaded to avoid skipping the foreground service on a cold start.
        lifecycleScope.launch {
            app.configRepository.isLoaded.first { it }
            if (app.configRepository.config.value.backgroundWorkEnabled) {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, MqttForegroundService::class.java))
            }
        }

        // Android 17+ blocks LAN connections without ACCESS_LOCAL_NETWORK. Only asked for once a
        // broker exists (not on a fresh install's Welcome screen), so the prompt has context.
        // The background service can't ask, so this is the one place it's requested.
        if (Build.VERSION.SDK_INT >= ANDROID_17 && !hasLocalNetworkPermission()) {
            lifecycleScope.launch {
                app.configRepository.config.first { it.brokers.isNotEmpty() }
                if (!hasLocalNetworkPermission()) {
                    localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        }

        setContent {
            Z2mDashTheme {
                AppNavHost()
            }
        }
    }

    @RequiresApi(ANDROID_17)
    private fun hasLocalNetworkPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        // Build.VERSION_CODES has no named constant for API 37 in this SDK.
        const val ANDROID_17 = 37
    }
}
