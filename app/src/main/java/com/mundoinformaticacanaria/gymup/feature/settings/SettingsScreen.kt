package com.mundoinformaticacanaria.gymup.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.backup.BackupManager
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupConfirmation
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupImpact
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupManager
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupResult
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    currentMode: ThemeMode,
    backupManager: BackupManager,
    historicalCleanupManager: HistoricalCleanupManager,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onCatalogMaintenance: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cutoffText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<HistoricalCleanupImpact?>(null) }
    var result by remember { mutableStateOf<HistoricalCleanupResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = "Ajustes", style = MaterialTheme.typography.headlineMedium) }
        item { Text(text = "Tema", style = MaterialTheme.typography.titleMedium) }
        item { ThemeButton("Sistema", ThemeMode.SYSTEM, currentMode, onThemeModeSelected) }
        item { ThemeButton("Claro", ThemeMode.LIGHT, currentMode, onThemeModeSelected) }
        item { ThemeButton("Oscuro", ThemeMode.DARK, currentMode, onThemeModeSelected) }

        item {
            Text(text = "Catálogos", style = MaterialTheme.typography.titleMedium)
            Text("Crear, renombrar o desactivar tipos de sesión, grupos musculares y equipos.")
            Button(onClick = onCatalogMaintenance, modifier = Modifier.fillMaxWidth()) {
                Text("Gestionar catálogos")
            }
        }

        item { BackupSection(backupManager = backupManager) }

        item {
            Text(text = "Limpieza histórica", style = MaterialTheme.typography.titleMedium)
            Text(
                "Elimina únicamente datos de sesiones anteriores a una fecha. " +
                    "Los ejercicios y demás datos maestros se conservan.",
            )
            Text(
                "Antes de eliminar puedes crear un backup completo desde la sección anterior.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            OutlinedTextField(
                value = cutoffText,
                onValueChange = {
                    cutoffText = it
                    preview = null
                    result = null
                    error = null
                },
                label = { Text("Fecha de corte (AAAA-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    val cutoff = runCatching { LocalDate.parse(cutoffText.trim()) }.getOrNull()
                    if (cutoff == null) {
                        error = "Introduce una fecha válida con formato AAAA-MM-DD."
                        preview = null
                    } else {
                        scope.launch {
                            runCatching { historicalCleanupManager.preview(cutoff) }
                                .onSuccess {
                                    preview = it
                                    result = null
                                    error = null
                                }
                                .onFailure { error = it.message ?: "No se pudo calcular la limpieza." }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Calcular datos afectados") }
        }

        preview?.let { impact ->
            item {
                Text("Fecha de corte: ${impact.cutoffDate}")
                Text("Sesiones: ${impact.sessions}")
                Text("Ejercicios de sesión: ${impact.sessionExercises}")
                Text("Series: ${impact.sessionSets}")
                Text("Registros asociados: ${impact.associatedRecords}")
                Text(
                    "La operación es irreversible. Se recomienda crear una copia de seguridad completa antes de continuar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { showConfirmation = true },
                    enabled = impact.totalTransactionalRecords > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Eliminar datos anteriores a la fecha") }
            }
        }

        result?.let { cleanupResult ->
            item {
                Text("Limpieza completada", style = MaterialTheme.typography.titleMedium)
                Text("Sesiones eliminadas: ${cleanupResult.deleted.sessions}")
                Text("Registros asociados eliminados: ${cleanupResult.deleted.associatedRecords}")
                Text("Espacio recuperado: ${formatBytes(cleanupResult.bytesReclaimed)}")
            }
        }

        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        }
    }

    if (showConfirmation) {
        val impact = preview
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirmar borrado irreversible") },
            text = {
                Text(
                    if (impact == null) {
                        "La vista previa ya no está disponible."
                    } else {
                        "Se eliminarán ${impact.sessions} sesiones y ${impact.associatedRecords} registros asociados " +
                            "anteriores a ${impact.cutoffDate}. Esta acción no se puede deshacer. " +
                            "Antes de continuar, guarda una copia de seguridad si necesitas conservar esos datos."
                    },
                )
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmation = false }) { Text("Cancelar") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentImpact = preview ?: return@Button
                        showConfirmation = false
                        scope.launch {
                            runCatching {
                                historicalCleanupManager.execute(
                                    cutoffDate = currentImpact.cutoffDate,
                                    confirmation = HistoricalCleanupConfirmation.IRREVERSIBLE_CONFIRMED,
                                )
                            }.onSuccess {
                                result = it
                                preview = null
                                error = null
                            }.onFailure { error = it.message ?: "No se pudo completar la limpieza." }
                        }
                    },
                    enabled = impact != null,
                ) { Text("Eliminar definitivamente") }
            },
        )
    }
}

@Composable
private fun ThemeButton(
    label: String,
    mode: ThemeMode,
    currentMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
) {
    if (currentMode == mode) {
        Button(onClick = { onThemeModeSelected(mode) }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · seleccionado")
        }
    } else {
        OutlinedButton(onClick = { onThemeModeSelected(mode) }, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
    }
}

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "no disponible"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
    else -> "${bytes / (1024L * 1024L)} MiB"
}
