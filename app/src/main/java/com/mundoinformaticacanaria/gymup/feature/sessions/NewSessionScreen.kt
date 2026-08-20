package com.mundoinformaticacanaria.gymup.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class SourceKind { EMPTY, ROUTINE, DUPLICATE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    trainingRepository: TrainingRepository,
    masterCatalogRepository: MasterCatalogRepository,
    exerciseCatalogRepository: ExerciseCatalogRepository,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val types by masterCatalogRepository.observeSessionTypes().collectAsStateWithLifecycle(initialValue = emptyList())
    val routines by trainingRepository.observeRoutines().collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by trainingRepository.observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeExercises by exerciseCatalogRepository.observeActiveExercises().collectAsStateWithLifecycle(initialValue = emptyList())

    var sourceKind by remember { mutableStateOf<SourceKind?>(null) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var selectedTypeId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var omittedWarning by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmedOmission by remember { mutableStateOf(false) }

    LaunchedEffect(types) {
        if (selectedTypeId == null && types.isNotEmpty()) selectedTypeId = types.first().id
    }
    LaunchedEffect(sourceKind, sourceId) {
        when (sourceKind) {
            SourceKind.ROUTINE -> sourceId?.let { trainingRepository.getRoutineDetail(it)?.routine?.suggestedSessionTypeId }?.let { selectedTypeId = it }
            SourceKind.DUPLICATE -> sourceId?.let { trainingRepository.getSessionDetail(it)?.sessionTypeId }?.let { selectedTypeId = it }
            else -> Unit
        }
        confirmedOmission = false
    }

    fun selectedSource(): SessionSource? = when (sourceKind) {
        SourceKind.EMPTY -> SessionSource.Empty
        SourceKind.ROUTINE -> sourceId?.let(SessionSource::Routine)
        SourceKind.DUPLICATE -> sourceId?.let(SessionSource::Duplicate)
        null -> null
    }

    suspend fun omittedForCurrentSource(): List<String> {
        val activeIds = activeExercises.map { it.id }.toSet()
        return when (sourceKind) {
            SourceKind.EMPTY, null -> emptyList()
            SourceKind.ROUTINE -> sourceId?.let { id ->
                trainingRepository.getRoutineDetail(id)?.exercises?.filterNot { it.isActive }?.map { "${it.nameEs} · ${it.nameEn}" }
            }.orEmpty()
            SourceKind.DUPLICATE -> sourceId?.let { id ->
                trainingRepository.getSessionDetail(id)?.exercises?.filter { it.exerciseId !in activeIds }?.map { "${it.nameEs} · ${it.nameEn}" }
            }.orEmpty()
        }
    }

    fun create() {
        val source = selectedSource()
        val typeId = selectedTypeId
        val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
        if (source == null) {
            error = "Selecciona cómo crear la sesión."
            return
        }
        if (date == null) {
            error = "La fecha debe tener formato AAAA-MM-DD."
            return
        }
        if (typeId == null) {
            error = "Selecciona un tipo de sesión."
            return
        }
        scope.launch {
            runCatching {
                val omitted = omittedForCurrentSource()
                if (omitted.isNotEmpty() && !confirmedOmission) {
                    omittedWarning = omitted
                    return@runCatching null
                }
                trainingRepository.createSession(date, typeId, name, note, source).sessionId
            }.onSuccess { sessionId ->
                if (sessionId != null) onCreated(sessionId)
            }.onFailure { throwable -> error = throwable.message ?: "No se pudo crear la sesión." }
        }
    }

    if (omittedWarning.isNotEmpty() && !confirmedOmission) {
        AlertDialog(
            onDismissRequest = { omittedWarning = emptyList() },
            title = { Text("Ejercicios desactivados") },
            text = { Text("No se copiarán:\n${omittedWarning.joinToString("\n")}") },
            confirmButton = {
                Button(onClick = {
                    confirmedOmission = true
                    omittedWarning = emptyList()
                    create()
                }) { Text("Crear sin ellos") }
            },
            dismissButton = { TextButton(onClick = { omittedWarning = emptyList() }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nueva sesión") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("1. Origen", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sourceKind == SourceKind.ROUTINE, onClick = { sourceKind = SourceKind.ROUTINE; sourceId = null }, label = { Text("Desde rutina") })
                FilterChip(selected = sourceKind == SourceKind.DUPLICATE, onClick = { sourceKind = SourceKind.DUPLICATE; sourceId = null }, label = { Text("Duplicar") })
            }
            FilterChip(selected = sourceKind == SourceKind.EMPTY, onClick = { sourceKind = SourceKind.EMPTY; sourceId = null }, label = { Text("Sesión vacía") })

            if (sourceKind == SourceKind.ROUTINE) {
                Text("Selecciona rutina")
                routines.forEach { routine ->
                    FilterChip(selected = sourceId == routine.id, onClick = { sourceId = routine.id }, label = { Text(routine.name) })
                }
            }
            if (sourceKind == SourceKind.DUPLICATE) {
                Text("Selecciona sesión")
                sessions.take(20).forEach { session ->
                    FilterChip(selected = sourceId == session.id, onClick = { sourceId = session.id }, label = { Text(session.name) })
                }
            }

            Text("2. Datos", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("Fecha (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth())
            Text("Tipo de sesión")
            types.forEach { type ->
                FilterChip(selected = selectedTypeId == type.id, onClick = { selectedTypeId = type.id }, label = { Text(type.name) })
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre opcional") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Nota general opcional") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::create, modifier = Modifier.fillMaxWidth()) { Text("Crear sesión") }
        }
    }
}
