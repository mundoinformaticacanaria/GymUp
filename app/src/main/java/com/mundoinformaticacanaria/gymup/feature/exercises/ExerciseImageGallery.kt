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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageItem
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                // Do not decode a File path directly: it can become a symlink
                // between Room observation and rendering. Use the manager's
                // bounded NOFOLLOW_LINKS descriptor read off the UI thread.
                val bitmap by produceState<ImageBitmap?>(
                    initialValue = null,
                    manager,
                    image.id,
                    image.storageKey,
                    image.localFile?.lastModified(),
                ) {
                    value = withContext(Dispatchers.IO) {
                        decodeUserImageBitmap(manager, image)
                    }
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

/** Gallery decoding must use the validated descriptor path, never BitmapFactory.decodeFile. */
internal fun decodeUserImageBitmap(
    manager: ExerciseImageManager,
    item: ExerciseImageItem,
): ImageBitmap? {
    val bytes = manager.readBytes(item) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
