package com.odiousapps.z2mdash

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.odiousapps.z2mdash.service.MqttForegroundService
import com.odiousapps.z2mdash.ui.navigation.AppNavHost
import com.odiousapps.z2mdash.ui.theme.Z2mDashTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as Z2mDashApplication
        // Config loads off the main thread, so config.value may still be the empty default here;
        // wait for isLoaded to avoid skipping the foreground service on a cold start.
        lifecycleScope.launch {
            app.configRepository.isLoaded.first { it }
            if (app.configRepository.config.value.backgroundWorkEnabled) {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, MqttForegroundService::class.java))
            }
        }

        setContent {
            Z2mDashTheme {
                AppNavHost()
            }
        }
    }
}
