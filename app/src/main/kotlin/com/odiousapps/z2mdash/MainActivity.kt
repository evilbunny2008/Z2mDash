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
        // ConfigRepository now loads its config off the main thread (see its own init{}
        // comment), so config.value here could still be the momentarily-empty default rather
        // than what's actually on disk - waiting for isLoaded first avoids skipping the
        // foreground service on a cold start just because this check ran before that finished.
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
