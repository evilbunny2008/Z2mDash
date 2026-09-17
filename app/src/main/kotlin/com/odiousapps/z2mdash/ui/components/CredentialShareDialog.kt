package com.odiousapps.z2mdash.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.CredentialShareClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shares one broker's hostname/username/password with another device via
 * MX3Launcher's generic credential relay (see CredentialShareClient) - a
 * short-lived code shown as both text and a QR, so it can be typed or
 * scanned on whatever's receiving these credentials, without them ever
 * being spoken aloud, screenshotted into a chat, or typed twice by hand.
 *
 * Starts the share as soon as this composes, then polls in the same
 * coroutine until the receiving side reveals it (or the code expires),
 * updating the dialog's own status line - no separate ViewModel needed
 * for something this self-contained and dialog-scoped.
 */
@Composable
fun CredentialShareDialog(broker: Broker, onDismiss: () -> Unit) {
    var session by remember { mutableStateOf<CredentialShareClient.ShareSession?>(null) }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf<CredentialShareClient.StatusResult>(CredentialShareClient.StatusResult.Pending) }
    var startError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(broker.id) {
        val fields = buildMap {
            put("Hostname", broker.host)
            if (broker.authEnabled) {
                put("Username", broker.username)
                put("Password", broker.password)
            }
        }
        val started = withContext(Dispatchers.IO) {
            CredentialShareClient.startShare(app = "Z2M Dash", label = broker.name, fields = fields)
        }
        if (started == null) {
            startError = "Couldn't reach mx3launcher.odiousapps.com - check your connection and try again."
            return@LaunchedEffect
        }
        session = started
        qrBitmap = withContext(Dispatchers.Default) {
            generateQrCodeBitmap(CredentialShareClient.viewUrl(started.code))
        }

        // Poll for pickup until it's viewed or the code expires - no fixed
        // iteration count, since how long that takes is entirely up to
        // whoever's receiving it.
        while (true) {
            delay(3000)
            val polled = withContext(Dispatchers.IO) { CredentialShareClient.pollStatus(started.token) }
            status = polled
            if (polled is CredentialShareClient.StatusResult.Viewed ||
                polled is CredentialShareClient.StatusResult.Expired
            ) {
                break
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share \"${broker.name}\"") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val error = startError
                val currentSession = session
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    currentSession == null -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Starting share...", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        qrBitmap?.let { bitmap ->
                            Image(bitmap = bitmap, contentDescription = "QR code for code ${currentSession.code}", modifier = Modifier.size(220.dp))
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            currentSession.code,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 8.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Scan this, or open mx3launcher.odiousapps.com/credential_view.php and enter the code, on the device that should receive these credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = when (status) {
                                is CredentialShareClient.StatusResult.Viewed -> "Received ✓"
                                is CredentialShareClient.StatusResult.Expired -> "Expired - reopen this broker to share again"
                                is CredentialShareClient.StatusResult.Error -> "Connection hiccup while checking status - code is still valid"
                                CredentialShareClient.StatusResult.Pending -> "Waiting for pickup…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status is CredentialShareClient.StatusResult.Viewed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (status is CredentialShareClient.StatusResult.Viewed) "Done" else "Cancel")
            }
        }
    )
}
