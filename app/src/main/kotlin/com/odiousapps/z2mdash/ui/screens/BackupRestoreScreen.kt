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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.BackupCodec
import com.odiousapps.z2mdash.data.restoreConfig
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
    val encryptEnabled = config.exportEncryptionEnabled

    // exportPasswordText is the dialog's live input; pendingExportPassword is the
    // confirmed value the exportLauncher callback uses once a destination is chosen.
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPasswordText by remember { mutableStateOf("") }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }

    // Both export scopes include brokers, so an unencrypted export always holds their passwords.
    var showUnencryptedWarning by remember { mutableStateOf(false) }

    // Holds a picked file's raw bytes until the user enters a password to decrypt it.
    var pendingEncryptedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var passwordDialogText by remember { mutableStateOf("") }
    var passwordDialogError by remember { mutableStateOf<String?>(null) }

    fun finishImport(json: String) {
        try {
            if (importScope == "BrokersOnly") {
                app.configRepository.importBrokersOnlyJson(json)
                snackbarMessage = app.getString(R.string.backup_brokers_imported)
            } else {
                restoreConfig(app, json, preserveBrokers = false)
                snackbarMessage = app.getString(R.string.backup_config_imported)
            }
        } catch (_: Exception) {
            snackbarMessage = app.getString(R.string.import_failed_invalid)
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
        // File may be plain JSON (pre-compression), raw gzip (brief .gz/.z2mbackup
        // era), or base64 gzip (current) - try newest format first, fall back for old backups.
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
        uri?.let {
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
            snackbarMessage = app.getString(R.string.backup_exported)
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
                title = { Text(stringResource(R.string.settings_backup)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.backup_export), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = exportScope == "Full",
                            onClick = { exportScope = "Full" },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text(stringResource(R.string.backup_scope_full)) }
                        SegmentedButton(
                            selected = exportScope == "BrokersOnly",
                            onClick = { exportScope = "BrokersOnly" },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text(stringResource(R.string.backup_scope_brokers)) }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.backup_encrypt)) },
                        supportingContent = { Text(stringResource(R.string.backup_encrypt_detail)) },
                        trailingContent = { Switch(checked = encryptEnabled, onCheckedChange = null) },
                        modifier = Modifier.toggleableRow(encryptEnabled) { enabled ->
                            app.configRepository.update { it.copy(exportEncryptionEnabled = enabled) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.backup_export_config)) },
                        supportingContent = {
                            Text(
                                if (exportScope == "BrokersOnly") {
                                    stringResource(R.string.backup_export_brokers_detail)
                                } else {
                                    stringResource(R.string.backup_export_full_detail)
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                        modifier = Modifier.clickable {
                            if (encryptEnabled) {
                                exportPasswordText = config.rememberedExportPassword.orEmpty()
                                showExportPasswordDialog = true
                            } else {
                                showUnencryptedWarning = true
                            }
                        }
                    )
                }
            }
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.backup_restore), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = importScope == "Full",
                            onClick = { importScope = "Full" },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text(stringResource(R.string.backup_replace_everything)) }
                        SegmentedButton(
                            selected = importScope == "BrokersOnly",
                            onClick = { importScope = "BrokersOnly" },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text(stringResource(R.string.backup_scope_brokers)) }
                    }
                    if (importScope == "BrokersOnly") {
                        Text(
                            stringResource(R.string.backup_restore_brokers_detail),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            stringResource(R.string.backup_restore_full_detail),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.backup_import_config)) },
                        supportingContent = { Text(stringResource(R.string.backup_import_config_detail)) },
                        leadingContent = { Icon(Icons.Default.Restore, contentDescription = null) },
                        modifier = Modifier.clickable {
                            // "*/*" not a gzip MIME filter - storage providers report gzip
                            // files inconsistently, which was hiding valid backups from the
                            // picker. Content is validated ourselves regardless.
                            try {
                                importLauncher.launch(arrayOf("*/*"))
                            } catch (_: ActivityNotFoundException) {
                                snackbarMessage = app.getString(R.string.no_file_picker)
                            }
                        }
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mqtt_backup_title)) },
                    supportingContent = { Text(stringResource(R.string.backup_mqtt_detail)) },
                    leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate("mqttBackup") }
                )
            }
        }
    }

    if (showUnencryptedWarning) {
        AlertDialog(
            onDismissRequest = { showUnencryptedWarning = false },
            title = { Text(stringResource(R.string.backup_unencrypted_title)) },
            text = {
                Text(stringResource(R.string.backup_unencrypted_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnencryptedWarning = false
                    try {
                        exportLauncher.launch(BackupCodec.newBackupFileName(brokersOnly = exportScope == "BrokersOnly"))
                    } catch (_: ActivityNotFoundException) {
                        snackbarMessage = app.getString(R.string.no_file_picker)
                    }
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnencryptedWarning = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showExportPasswordDialog = false },
            title = { Text(stringResource(R.string.backup_set_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_set_password_text))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPasswordText,
                        onValueChange = { exportPasswordText = it },
                        label = { Text(stringResource(R.string.backup_password)) },
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
                            snackbarMessage = app.getString(R.string.no_file_picker)
                        }
                    },
                    enabled = exportPasswordText.isNotBlank()
                ) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    val encryptedBytes = pendingEncryptedBytes
    if (encryptedBytes != null) {
        AlertDialog(
            onDismissRequest = { pendingEncryptedBytes = null },
            title = { Text(stringResource(R.string.backup_encrypted_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_encrypted_text))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordDialogText,
                        onValueChange = { passwordDialogText = it; passwordDialogError = null },
                        label = { Text(stringResource(R.string.backup_password)) },
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
                        passwordDialogError = app.getString(R.string.backup_wrong_password)
                    } else {
                        val json = try {
                            BackupCodec.decompress(decrypted)
                        } catch (_: Exception) {
                            null
                        }
                        if (json == null) {
                            passwordDialogError = app.getString(R.string.backup_wrong_password)
                        } else {
                            pendingEncryptedBytes = null
                            finishImport(json)
                        }
                    }
                }) { Text(stringResource(R.string.backup_unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingEncryptedBytes = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
