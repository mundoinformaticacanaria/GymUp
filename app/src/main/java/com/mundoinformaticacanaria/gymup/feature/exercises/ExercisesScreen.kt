package com.mundoinformaticacanaria.gymup.feature.exercises

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageItem
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.rankExercises
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(
    exerciseCatalogRepository: ExerciseCatalogRepository,
    masterCatalogRepository: MasterCatalogRepository,
    historyRepository: HistoryRepository,
    exerciseImageManager: ExerciseImageManager,
    onNewExercise: () -> Unit,
    onEditExercise: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val exercises by exerciseCatalogRepository.observeActiveExercises().collectAsStateWithLifecycle(initialValue = emptyList())
    val muscleGroups by masterCatalogRepository.observeMuscleGroups().collectAsStateWithLifecycle(initialValue = emptyList())
    val metadata by historyRepository.observeExerciseSearchMetadata().collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var muscleGroupId by remember { mutableStateOf<String?>(null) }
    var pendingImageExerciseId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ranked = remember(exercises, metadata, query, muscleGroupId) {
        rankExercises(exercises, metadata, query, muscleGroupId)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val exerciseId = pendingImageExerciseId
        pendingImageExerciseId = null
        if (uri != null && exerciseId != null) {
            scope.launch {
                runCatching {
                    val mime = context.contentResolver.getType(uri)
                    require(mime == null || mime.startsWith("image/")) { "El archivo seleccionado no es una imagen" }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("No se pudo leer la imagen seleccionada")
                    exerciseImageManager.addUserImage(exerciseId, bytes)
                }.onSuccess {
                    message = "Imagen añadida"
                }.onFailure { error ->
                    message = error.message ?: "No se pudo añadir la imagen"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ejercicios") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onNewExercise, modifier = Modifier.fillMaxWidth()) {
                Text("Nuevo ejercicio")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar por nombre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = muscleGroupId == null, onClick = { muscleGroupId = null }, label = { Text("Todos") })
                }
                items(muscleGroups, key = { it.id }) { group ->
                    FilterChip(
                        selected = muscleGroupId == group.id,
                        onClick = { muscleGroupId = if (muscleGroupId == group.id) null else group.id },
                        label = { Text(group.name) },
                    )
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("${ranked.size} ejercicio(s)", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ranked, key = { it.id }) { exercise ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${exercise.nameEs} · ${exercise.nameEn}", style = MaterialTheme.typography.titleMedium)
                            val meta = metadata[exercise.id]
                            val support = buildList {
                                if (exercise.isFavorite) add("Favorito")
                                meta?.lastExecutionDate?.let { add("Último: $it") }
                                if (meta?.inRoutine == true) add("En rutina")
                            }
                            if (support.isNotEmpty()) Text(support.joinToString(" · "))
                            ExerciseImagesSection(
                                exerciseId = exercise.id,
                                manager = exerciseImageManager,
                                onAdd = {
                                    pendingImageExerciseId = exercise.id
                                    message = null
                                    imagePicker.launch("image/*")
                                },
                                onMessage = { message = it },
                            )
                            TextButton(onClick = { onEditExercise(exercise.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Editar ficha")
                            }
                            TextButton(
                                onClick = { scope.launch { exerciseCatalogRepository.setFavorite(exercise.id, !exercise.isFavorite) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (exercise.isFavorite) "★ Quitar favorito" else "☆ Marcar favorito") }
                            TextButton(onClick = { onOpenHistory(exercise.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Ver histórico y gráficas")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseImagesSection(
    exerciseId: String,
    manager: ExerciseImageManager,
    onAdd: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val images by manager.observeImages(exerciseId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    if (images.isNotEmpty()) {
        Text("Imágenes (${images.size}/3)", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images, key = ExerciseImageItem::id) { image ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val file = image.localFile
                    val bitmap = remember(file?.absolutePath, file?.lastModified()) {
                        file?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Imagen del ejercicio",
                            modifier = Modifier.size(96.dp),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text("Imagen ${image.position}")
                    }
                    if (image.sourceType == "USER") {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { manager.deleteUserImage(image.id) }
                                        .onSuccess { onMessage("Imagen eliminada") }
                                        .onFailure { onMessage(it.message ?: "No se pudo eliminar la imagen") }
                                }
                            },
                        ) { Text("Eliminar") }
                    }
                }
            }
        }
    }

    if (images.size < ExerciseImageManager.MAX_IMAGES_PER_EXERCISE) {
        TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Añadir imagen (${images.size}/3)")
        }
    }
}
