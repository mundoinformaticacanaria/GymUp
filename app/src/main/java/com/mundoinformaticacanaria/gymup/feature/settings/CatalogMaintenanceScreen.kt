package com.mundoinformaticacanaria.gymup.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind
import com.mundoinformaticacanaria.gymup.domain.repository.MasterMaintenanceItem
import kotlinx.coroutines.launch

private data class MasterEditRequest(
    val kind: MasterCatalogKind,
    val item: MasterMaintenanceItem? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogMaintenanceScreen(
    repository: CatalogMaintenanceRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sessionTypes by repository.observeMasters(MasterCatalogKind.SESSION_TYPE)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val muscleGroups by repository.observeMasters(MasterCatalogKind.MUSCLE_GROUP)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val equipment by repository.observeMasters(MasterCatalogKind.EQUIPMENT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var editRequest by remember { mutableStateOf<MasterEditRequest?>(null) }
    var pendingDeactivate by remember { mutableStateOf<MasterEditRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { message = null }
                .onFailure { message = it.message ?: "No se pudo aplicar el cambio" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catálogos") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Los cambios afectan a nuevas selecciones. Los snapshots históricos no se reescriben.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            masterSection(
                title = "Tipos de sesión",
                kind = MasterCatalogKind.SESSION_TYPE,
                items = sessionTypes,
                onCreate = { editRequest = MasterEditRequest(MasterCatalogKind.SESSION_TYPE) },
                onEdit = { editRequest = MasterEditRequest(MasterCatalogKind.SESSION_TYPE, it) },
                onDeactivate = { pendingDeactivate = MasterEditRequest(MasterCatalogKind.SESSION_TYPE, it) },
            )
            masterSection(
                title = "Grupos musculares",
                kind = MasterCatalogKind.MUSCLE_GROUP,
                items = muscleGroups,
                onCreate = { editRequest = MasterEditRequest(MasterCatalogKind.MUSCLE_GROUP) },
                onEdit = { editRequest = MasterEditRequest(MasterCatalogKind.MUSCLE_GROUP, it) },
                onDeactivate = { pendingDeactivate = MasterEditRequest(MasterCatalogKind.MUSCLE_GROUP, it) },
            )
            masterSection(
                title = "Equipo",
                kind = MasterCatalogKind.EQUIPMENT,
                items = equipment,
                onCreate = { editRequest = MasterEditRequest(MasterCatalogKind.EQUIPMENT) },
                onEdit = { editRequest = MasterEditRequest(MasterCatalogKind.EQUIPMENT, it) },
                onDeactivate = { pendingDeactivate = MasterEditRequest(MasterCatalogKind.EQUIPMENT, it) },
            )
        }
    }

    editRequest?.let { request ->
        var name by remember(request) { mutableStateOf(request.item?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editRequest = null },
            title = { Text(if (request.item == null) "Crear ${request.kind.label()}" else "Renombrar ${request.kind.label()}") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                )
            },
            dismissButton = { TextButton(onClick = { editRequest = null }) { Text("Cancelar") } },
            confirmButton = {
                Button(
                    onClick = {
                        val current = editRequest ?: return@Button
                        editRequest = null
                        runAction {
                            if (current.item == null) repository.createMaster(current.kind, name)
                            else repository.renameMaster(current.kind, current.item.id, name)
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Guardar") }
            },
        )
    }

    pendingDeactivate?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDeactivate = null },
            title = { Text("Desactivar ${request.kind.label()}") },
            text = {
                Text(
                    "${request.item?.name.orEmpty()} dejará de estar disponible para nuevas selecciones. " +
                        "El histórico existente se conserva.",
                )
            },
            dismissButton = { TextButton(onClick = { pendingDeactivate = null }) { Text("Cancelar") } },
            confirmButton = {
                Button(onClick = {
                    val current = pendingDeactivate ?: return@Button
                    pendingDeactivate = null
                    runAction { repository.deactivateMaster(current.kind, requireNotNull(current.item).id) }
                }) { Text("Desactivar") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.masterSection(
    title: String,
    kind: MasterCatalogKind,
    items: List<MasterMaintenanceItem>,
    onCreate: () -> Unit,
    onEdit: (MasterMaintenanceItem) -> Unit,
    onDeactivate: (MasterMaintenanceItem) -> Unit,
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text("Añadir ${kind.label()}")
            }
        }
    }
    items(items, key = { "${kind.name}:${it.id}" }) { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (item.protected) "${item.name} · protegido" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!item.protected) {
                    OutlinedButton(onClick = { onEdit(item) }, modifier = Modifier.fillMaxWidth()) { Text("Renombrar") }
                    TextButton(onClick = { onDeactivate(item) }, modifier = Modifier.fillMaxWidth()) { Text("Desactivar") }
                }
            }
        }
    }
}

private fun MasterCatalogKind.label(): String = when (this) {
    MasterCatalogKind.SESSION_TYPE -> "tipo de sesión"
    MasterCatalogKind.MUSCLE_GROUP -> "grupo muscular"
    MasterCatalogKind.EQUIPMENT -> "equipo"
}
