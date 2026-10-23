package com.mundoinformaticacanaria.gymup.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

private enum class SettingsSection { THEME, BACKUP, CLEANUP }

/** Reject a stale or edited cutoff before displaying or confirming irreversible cleanup. */
internal fun cleanupPreviewMatchesCutoff(cutoffText: String, impact: HistoricalCleanupImpact?): Boolean =
    impact != null && runCatching { LocalDate.parse(cutoffText.trim()) }.getOrNull() == impact.cutoffDate

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
    val listState = rememberLazyListState()
    var selectedSection by remember { mutableStateOf<SettingsSection?>(null) }
    var cutoffText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<HistoricalCleanupImpact?>(null) }
    var result by remember { mutableStateOf<HistoricalCleanupResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }
    var cleanupInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(selectedSection) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineMedium) }
        if (selectedSection == null) {
            item {
                Text("Elige qué quieres configurar", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { selectedSection = SettingsSection.THEME }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apariencia · Sistema / Claro / Oscuro")
                }
                OutlinedButton(onClick = onCatalogMaintenance, modifier = Modifier.fillMaxWidth()) {
                    Text("Catálogos · Tipos, grupos y equipo")
                }
                OutlinedButton(onClick = { selectedSection = SettingsSection.BACKUP }, modifier = Modifier.fillMaxWidth()) {
                    Text("Copia de seguridad · Exportar / importar")
                }
                OutlinedButton(onClick = { selectedSection = SettingsSection.CLEANUP }, modifier = Modifier.fillMaxWidth()) {
                    Text("Limpieza histórica · Ver y eliminar registros")
                }
            }
        } else {
            item {
                OutlinedButton(
                    onClick = { selectedSection = null; showConfirmation = false },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Volver al índice de Ajustes") }
            }
            when (selectedSection) {
                SettingsSection.THEME -> {
                    item { Text(text = "Tema", style = MaterialTheme.typography.titleMedium) }
                    item { ThemeButton("Sistema", ThemeMode.SYSTEM, currentMode, onThemeModeSelected) }
                    item { ThemeButton("Claro", ThemeMode.LIGHT, currentMode, onThemeModeSelected) }
                    item { ThemeButton("Oscuro", ThemeMode.DARK, currentMode, onThemeModeSelected) }

                }
                SettingsSection.BACKUP -> {
                    item { BackupSection(backupManager = backupManager) }

                }
                SettingsSection.CLEANUP -> {
                    item {
                        Text(text = "Limpieza histórica", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Elimina únicamente datos de sesiones anteriores a una fecha. " +
                                "Los ejercicios y demás datos maestros se conservan.",
                        )
                        Text(
                            "Antes de eliminar puedes crear un backup completo desde Copia de seguridad en Ajustes.",
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
                                showConfirmation = false
                            },
                            label = { Text("Fecha de corte (AAAA-MM-DD)") },
                            enabled = !cleanupInProgress,
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
                                                if (cutoffText.trim() == cutoff.toString()) {
                                                    preview = it
                                                    result = null
                                                    error = null
                                                }
                                            }
                                            .onFailure {
                                                if (cutoffText.trim() == cutoff.toString()) {
                                                    error = it.message ?: "No se pudo calcular la limpieza."
                                                }
                                            }
                                    }
                                }
                            },
                            enabled = !cleanupInProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Calcular datos afectados") }
                    }

                    preview?.takeIf { cleanupPreviewMatchesCutoff(cutoffText, it) }?.let { impact ->
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
                                enabled = impact.totalTransactionalRecords > 0 && !cleanupInProgress,
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
                }
                null -> Unit
            }
        }
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        }
    }

    if (showConfirmation) {
        val impact = preview?.takeIf { cleanupPreviewMatchesCutoff(cutoffText, it) }
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
                        val currentImpact = preview?.takeIf {
                            cleanupPreviewMatchesCutoff(cutoffText, it)
                        } ?: return@Button
                        if (cleanupInProgress) return@Button
                        cleanupInProgress = true
                        showConfirmation = false
                        scope.launch {
                            try {
                                result = historicalCleanupManager.execute(
                                    cutoffDate = currentImpact.cutoffDate,
                                    confirmation = HistoricalCleanupConfirmation.IRREVERSIBLE_CONFIRMED,
                                )
                                preview = null
                                error = null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "No se pudo completar la limpieza."
                            } finally {
                                cleanupInProgress = false
                            }
                        }
                    },
                    enabled = impact != null && !cleanupInProgress,
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
