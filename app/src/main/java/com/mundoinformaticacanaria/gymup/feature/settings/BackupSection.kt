package com.mundoinformaticacanaria.gymup.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mundoinformaticacanaria.gymup.data.backup.AndroidBackupFiles
import com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult
import com.mundoinformaticacanaria.gymup.data.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupSection(backupManager: BackupManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AndroidBackupFiles.MIME_TYPE),
    ) { destination ->
        val bytes = pendingExport
        pendingExport = null
        if (destination != null && bytes != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        AndroidBackupFiles.writeToDocument(context, destination, bytes)
                    }
                }.onSuccess {
                    status = "Backup guardado correctamente."
                    error = null
                }.onFailure {
                    error = it.message ?: "No se pudo guardar el backup."
                    status = null
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { AndroidBackupFiles.readFromDocument(context, source) }
                }.onSuccess {
                    pendingImport = it
                    status = null
                    error = null
                }.onFailure {
                    error = it.message ?: "No se pudo leer el backup."
                    status = null
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Copia de seguridad", style = MaterialTheme.typography.titleMedium)
        Text(
            "El backup contiene todos los datos de GymUp y las imágenes personalizadas. " +
                "No está protegido con contraseña ni cifrado propio: guárdalo en un lugar seguro.",
        )
        Button(
            onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { backupManager.export() } }
                        .onSuccess {
                            pendingExport = it
                            status = null
                            error = null
                            exportLauncher.launch(AndroidBackupFiles.defaultFileName())
                        }
                        .onFailure {
                            error = it.message ?: "No se pudo crear el backup."
                            status = null
                        }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Crear backup completo") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf(AndroidBackupFiles.MIME_TYPE, "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Importar backup") }
        Text("Importar sustituye todos los datos actuales; no se realiza merge.")
        status?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    pendingImport?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Reemplazar todos los datos") },
            text = {
                Text(
                    "GymUp validará completamente el ZIP antes de modificar nada. Si es válido, " +
                        "todos los datos actuales serán sustituidos por los del backup. Esta acción no hace merge.",
                )
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingImport = null }) { Text("Cancelar") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingImport = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { backupManager.importReplaceAll(archive) }
                            }.onSuccess { result ->
                                when (result) {
                                    is BackupImportResult.Imported -> {
                                        status = "Backup importado correctamente (${result.imageCount} imagen(es))."
                                        error = null
                                    }
                                    is BackupImportResult.Rejected -> {
                                        error = result.reason
                                        status = null
                                    }
                                }
                            }.onFailure {
                                error = it.message ?: "No se pudo importar el backup."
                                status = null
                            }
                        }
                    },
                ) { Text("Validar y reemplazar") }
            },
        )
    }
}
