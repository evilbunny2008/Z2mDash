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
import com.odiousapps.z2mdash.data.CredentialShareClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows a code/QR to pull a broker's hostname/username/password from a saved sync.odiousapps.com
 * preset, avoiding a hand-typed or voice/chat-dictated password.
 *
 * Starts the request on composition, then polls until resolved or expired, calling [onImported]
 * with whatever fields came back (only "Hostname" is checked here - the caller interprets the
 * rest, e.g. AddEditBrokerScreen's "Username"/"Password"/"Protocol").
 */
@Composable
fun CredentialImportDialog(
    onImported: (fields: Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var session by remember { mutableStateOf<CredentialShareClient.ImportSession?>(null) }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val started = withContext(Dispatchers.IO) {
            CredentialShareClient.startImport(label = "New broker")
        }
        if (started == null) {
            errorMessage = "Couldn't reach sync.odiousapps.com - check your connection and try again."
            return@LaunchedEffect
        }
        session = started
        qrBitmap = withContext(Dispatchers.Default) {
            generateQrCodeBitmap(CredentialShareClient.viewUrl(started.code))
        }

        // Poll until resolved or expired - no fixed iteration count since timing is up to
        // whoever's picking a preset to send.
        while (true) {
            delay(3000.milliseconds)
            when (val polled = withContext(Dispatchers.IO) { CredentialShareClient.pollStatus(started.token) }) {
                is CredentialShareClient.StatusResult.Resolved -> {
                    if (polled.fields["Hostname"].isNullOrBlank()) {
                        errorMessage = "That preset didn't include a Hostname field - check it on sync.odiousapps.com and try again."
                    } else {
                        onImported(polled.fields)
                    }
                    return@LaunchedEffect
                }
                CredentialShareClient.StatusResult.Expired -> {
                    errorMessage = "Code expired - reopen this to try again."
                    return@LaunchedEffect
                }
                is CredentialShareClient.StatusResult.Error, CredentialShareClient.StatusResult.Pending -> {
                    // Transient network hiccup or just still waiting - keep polling.
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import broker credentials") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val error = errorMessage
                val currentSession = session
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    currentSession == null -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Starting...", style = MaterialTheme.typography.bodySmall)
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
                            "Scan this, or open sync.odiousapps.com/credential_view.php and enter the code, then pick a saved \"Z2M Dash\" credential to send.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Waiting for pickup…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
