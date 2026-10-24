package com.mundoinformaticacanaria.gymup.feature.settings

import androidx.activity.compose.BackHandler
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

/** Serializes backup operations that may replace all local data. */
internal class BackupActionGate {
    var running by mutableStateOf(false)
        private set

    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    fun finish() { running = false }
}

@Composable
fun BackupSection(
    backupManager: BackupManager,
    onBusyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val actionGate = remember { BackupActionGate() }
    BackHandler(enabled = actionGate.running) { /* Do not cancel an in-flight replace-all. */ }

    fun launchExclusive(fallbackError: String, block: suspend () -> Unit) {
        if (!actionGate.tryStart()) return
        onBusyChanged(true)
        scope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: fallbackError
                status = null
            } finally {
                actionGate.finish()
                onBusyChanged(false)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AndroidBackupFiles.MIME_TYPE),
    ) { destination ->
        val bytes = pendingExport
        pendingExport = null
        if (destination != null && bytes != null) {
            launchExclusive("No se pudo guardar el backup.") {
                withContext(Dispatchers.IO) {
                    AndroidBackupFiles.writeToDocument(context, destination, bytes)
                }
                status = "Backup guardado correctamente."
                error = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source != null) {
            launchExclusive("No se pudo leer el backup.") {
                pendingImport = withContext(Dispatchers.IO) {
                    AndroidBackupFiles.readFromDocument(context, source)
                }
                status = null
                error = null
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
                launchExclusive("No se pudo crear el backup.") {
                    pendingExport = withContext(Dispatchers.IO) { backupManager.export() }
                    status = null
                    error = null
                    exportLauncher.launch(AndroidBackupFiles.defaultFileName())
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !actionGate.running,
        ) { Text("Crear backup completo") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf(AndroidBackupFiles.MIME_TYPE, "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !actionGate.running,
        ) { Text("Importar backup") }
        Text("Importar sustituye todos los datos actuales; no se realiza merge.")
        if (actionGate.running) Text("Operación de backup en curso. Espera a que termine.")
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
                        if (actionGate.running) return@Button
                        pendingImport = null
                        launchExclusive("No se pudo importar el backup.") {
                            when (val result = withContext(Dispatchers.IO) {
                                backupManager.importReplaceAll(archive)
                            }) {
                                is BackupImportResult.Imported -> {
                                    status = "Backup importado correctamente (${result.imageCount} imagen(es))."
                                    error = null
                                }
                                is BackupImportResult.Rejected -> {
                                    error = result.reason
                                    status = null
                                }
                            }
                        }
                    },
                    enabled = !actionGate.running,
                ) { Text("Validar y reemplazar") }
            },
        )
    }
}
