package com.odiousapps.z2mdash.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

object NotificationPermission {
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * Returns the permission string if the device supports it (API 33+),
     * otherwise returns null.
     */
    fun getPostNotificationsPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            POST_NOTIFICATIONS
        } else {
            null
        }
    }
}

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

/**
 * POST_NOTIFICATIONS is only asked for when the user turns on something that notifies (an alert,
 * background work, a test notification) rather than on first launch.
 */
class NotificationPermissionState(
    granted: Boolean,
    private val requestPermission: () -> Unit,
    private val openSettings: () -> Unit
) {
    var granted by mutableStateOf(granted)
        internal set

    /** Shows the system prompt if notifications aren't already allowed - a no-op otherwise. */
    fun request() {
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermission()
    }

    /** After two denials Android stops showing the prompt, so this is the way back in. */
    fun openAppNotificationSettings() = openSettings()
}

@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    lateinit var state: NotificationPermissionState
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.granted = granted
    }
    state = remember {
        NotificationPermissionState(
            granted = hasNotificationPermission(context),
            //requestPermission = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            requestPermission = {
                NotificationPermission.getPostNotificationsPermission()?.let {
                    launcher.launch(it)
                }
            },
            openSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
    }
    // Picks up a change made in system settings while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.granted = hasNotificationPermission(context)
    }
    return state
}
