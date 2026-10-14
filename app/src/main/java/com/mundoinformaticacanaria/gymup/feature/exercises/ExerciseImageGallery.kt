package com.mundoinformaticacanaria.gymup.feature.exercises

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageItem
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager

@Composable
fun ExerciseImageGallery(
    exerciseId: String,
    manager: ExerciseImageManager,
    modifier: Modifier = Modifier,
) {
    val images by manager.observeImages(exerciseId).collectAsStateWithLifecycle(initialValue = emptyList())
    if (images.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Imágenes", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images, key = ExerciseImageItem::id) { image ->
                val file = image.localFile
                val bitmap = remember(file?.absolutePath, file?.lastModified()) {
                    file?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "${image.position}. Imagen de ayuda del ejercicio",
                        modifier = Modifier.size(140.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("Imagen ${image.position} no disponible")
                }
            }
        }
    }
}
