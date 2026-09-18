package com.odiousapps.z2mdash.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.BackupCodec
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import com.odiousapps.z2mdash.ui.tv.tvAwareKeyboardOptions
import com.odiousapps.z2mdash.ui.tv.toggleableRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as Z2mDashApplication
    val context = LocalContext.current
    val config by app.configRepository.config.collectAsState()

    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    var exportScope by remember { mutableStateOf("Full") } // "Full" or "BrokersOnly"
    var importScope by remember { mutableStateOf("Full") } // "Full" or "BrokersOnly"
    var encryptEnabled by remember { mutableStateOf(false) }

    // Shown when the user taps Export with encryption enabled, before the
    // file picker launches - exportPasswordText is the dialog's live input,
    // pendingExportPassword is the confirmed value the exportLauncher
    // callback below actually uses once the file destination is chosen.
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPasswordText by remember { mutableStateOf("") }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }

    // Set once a picked file turns out to be encrypted - holds the raw file
    // bytes until the user enters a password, since decryption (and the
    // subsequent import) can't happen until then.
    var pendingEncryptedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var passwordDialogText by remember { mutableStateOf("") }
    var passwordDialogError by remember { mutableStateOf<String?>(null) }

    fun finishImport(json: String) {
        try {
            if (importScope == "BrokersOnly") {
                app.configRepository.importBrokersOnlyJson(json)
                snackbarMessage = "Brokers imported"
            } else {
                app.configRepository.importJson(json)
                snackbarMessage = "Configuration imported"
            }
        } catch (_: Exception) {
            snackbarMessage = "Import failed: not a valid config file"
        }
    }

    fun handlePickedBytes(bytes: ByteArray) {
        if (BackupCodec.isEncrypted(bytes)) {
            pendingEncryptedBytes = bytes
            passwordDialogText = ""
            passwordDialogError = null
            return
        }
        val text = bytes.toString(Charsets.UTF_8)
        // Three formats this file could be, oldest first: plain JSON
        // (pre-compression), raw gzip bytes (the brief .gz/.z2mbackup era),
        // or base64-encoded gzip (current). Try newest-likeliest first so
        // old backups still import fine either way.
        val json = try {
            BackupCodec.decompressFromBase64(text)
        } catch (_: Exception) {
            try {
                BackupCodec.decompress(bytes)
            } catch (_: Exception) {
                text
            }
        }
        finishImport(json)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        uri?.let { it ->
            val json = if (exportScope == "BrokersOnly") {
                app.configRepository.exportBrokersOnlyJson()
            } else {
                app.configRepository.exportJson()
            }
            val compressed = BackupCodec.compress(json)
            val password = pendingExportPassword
            val output = if (encryptEnabled && !password.isNullOrBlank()) {
                BackupCodec.encrypt(compressed, password)
            } else {
                compressed
            }
            context.contentResolver.openOutputStream(it, "wt")?.use { out -> out.write(output) }
            pendingExportPassword = null
            snackbarMessage = "Configuration exported"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { input ->
                handlePickedBytes(input.readBytes())
            }
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = exportScope == "Full",
                            onClick = { exportScope = "Full" },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("Full Configuration") }
                        SegmentedButton(
                            selected = exportScope == "BrokersOnly",
                            onClick = { exportScope = "BrokersOnly" },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Brokers Only") }
                    }
                    ListItem(
                        headlineContent = { Text("Encrypt Backup") },
                        supportingContent = { Text("Protect the exported file with a password you choose") },
                        trailingContent = { Switch(checked = encryptEnabled, onCheckedChange = null) },
                        modifier = Modifier.toggleableRow(encryptEnabled) { encryptEnabled = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text("Export Configuration") },
                        supportingContent = {
                            Text(
                                if (exportScope == "BrokersOnly") {
                                    "Save just your broker connection details as a compressed file"
                                } else {
                                    "Save your brokers, groups and panels as a compressed file you own"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                        modifier = Modifier.clickable {
                            if (encryptEnabled) {
                                exportPasswordText = config.rememberedExportPassword.orEmpty()
                                showExportPasswordDialog = true
                            } else {
                                try {
                                    exportLauncher.launch(BackupCodec.newBackupFileName(brokersOnly = exportScope == "BrokersOnly"))
                                } catch (_: ActivityNotFoundException) {
                                    snackbarMessage = "No file picker app is available on this device."
                                }
                            }
                        }
                    )
                }
            }
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Restore", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = importScope == "Full",
                            onClick = { importScope = "Full" },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("Replace Everything") }
                        SegmentedButton(
                            selected = importScope == "BrokersOnly",
                            onClick = { importScope = "BrokersOnly" },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Brokers Only") }
                    }
                    if (importScope == "BrokersOnly") {
                        Text(
                            "Merges brokers from the file into your current setup \u2013 groups and panels are left untouched.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Replaces your entire configuration with what's in the file.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text("Import Configuration") },
                        supportingContent = { Text("Restore from a previously exported backup file") },
                        leadingContent = { Icon(Icons.Default.Restore, contentDescription = null) },
                        modifier = Modifier.clickable {
                            // "*/*" rather than filtering by MIME type - different
                            // storage providers report gzip files inconsistently
                            // (application/x-gzip vs application/gzip vs
                            // octet-stream), which was silently hiding valid
                            // backups from the picker. The app already validates
                            // content itself regardless of what the OS reports.
                            try {
                                importLauncher.launch(arrayOf("*/*"))
                            } catch (_: ActivityNotFoundException) {
                                snackbarMessage = "No file picker app is available on this device."
                            }
                        }
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Backup / Restore via MQTT") },
                    supportingContent = { Text("Publish or read a compressed backup on a broker topic, instead of a file") },
                    leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("mqttBackup") }
                )
            }
        }
    }

    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showExportPasswordDialog = false },
            title = { Text("Set Export Password") },
            text = {
                Column {
                    Text("Choose a password to encrypt this backup with. It's remembered for next time - you'll need it again to restore.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPasswordText,
                        onValueChange = { exportPasswordText = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExportPassword = exportPasswordText
                        app.configRepository.update { it.copy(rememberedExportPassword = exportPasswordText) }
                        showExportPasswordDialog = false
                        try {
                            exportLauncher.launch(BackupCodec.newBackupFileName(brokersOnly = exportScope == "BrokersOnly"))
                        } catch (_: ActivityNotFoundException) {
                            snackbarMessage = "No file picker app is available on this device."
                        }
                    },
                    enabled = exportPasswordText.isNotBlank()
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordDialog = false }) { Text("Cancel") }
            }
        )
    }

    val encryptedBytes = pendingEncryptedBytes
    if (encryptedBytes != null) {
        AlertDialog(
            onDismissRequest = { pendingEncryptedBytes = null },
            title = { Text("Encrypted Backup") },
            text = {
                Column {
                    Text("This backup is encrypted. Enter the password it was encrypted with.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordDialogText,
                        onValueChange = { passwordDialogText = it; passwordDialogError = null },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = passwordDialogError != null,
                        supportingText = passwordDialogError?.let { { Text(it) } },
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val decrypted = BackupCodec.decrypt(encryptedBytes, passwordDialogText)
                    if (decrypted == null) {
                        passwordDialogError = "Wrong password, or the file is corrupted"
                    } else {
                        val json = try {
                            BackupCodec.decompress(decrypted)
                        } catch (_: Exception) {
                            null
                        }
                        if (json == null) {
                            passwordDialogError = "Wrong password, or the file is corrupted"
                        } else {
                            pendingEncryptedBytes = null
                            finishImport(json)
                        }
                    }
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEncryptedBytes = null }) { Text("Cancel") }
            }
        )
    }
}
