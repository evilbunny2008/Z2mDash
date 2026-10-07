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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.data.CredentialShareClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows a code/QR to pull a broker's hostname/username/password from a saved sync.odiousapps.com
 * preset, avoiding a hand-typed or voice/chat-dictated password.
 *
 * Starts the request once the user agrees to the disclosure, then polls until resolved or expired, calling [onImported]
 * with whatever fields came back (only "Hostname" is checked here - the caller interprets the
 * rest, e.g. AddEditBrokerScreen's "Username"/"Password"/"Protocol").
 */
@Composable
fun CredentialImportDialog(
    onImported: (fields: Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val resources = LocalResources.current
    var session by remember { mutableStateOf<CredentialShareClient.ImportSession?>(null) }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Nothing is sent to the relay until the user has read what it is and agreed to use it.
    var consented by remember { mutableStateOf(false) }

    LaunchedEffect(consented) {
        if (!consented) return@LaunchedEffect
        val started = withContext(Dispatchers.IO) {
            CredentialShareClient.startImport(label = "New broker")
        }
        if (started == null) {
            errorMessage = resources.getString(R.string.cred_unreachable, CredentialShareClient.SERVICE_HOST)
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
                        errorMessage = resources.getString(R.string.cred_no_hostname, CredentialShareClient.SERVICE_HOST)
                    } else {
                        onImported(polled.fields)
                    }
                    return@LaunchedEffect
                }
                CredentialShareClient.StatusResult.Expired -> {
                    errorMessage = resources.getString(R.string.cred_expired)
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
        title = { Text(stringResource(R.string.cred_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val error = errorMessage
                val currentSession = session
                when {
                    !consented -> Text(
                        stringResource(R.string.cred_consent, CredentialShareClient.SERVICE_HOST),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    currentSession == null -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.cred_starting), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        qrBitmap?.let { bitmap ->
                            Image(bitmap = bitmap, contentDescription = stringResource(R.string.cred_qr_description, currentSession.code), modifier = Modifier.size(220.dp))
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
                            stringResource(
                                R.string.cred_instructions,
                                stringResource(R.string.app_name),
                                CredentialShareClient.VIEW_PAGE
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.cred_waiting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (!consented) {
                TextButton(onClick = { consented = true }) { Text(stringResource(R.string.common_continue)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
