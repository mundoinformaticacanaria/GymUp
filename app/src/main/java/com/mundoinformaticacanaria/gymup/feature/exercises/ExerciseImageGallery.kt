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
                // The revision is observed off-thread; never stat a path in composition.
                val bitmap by produceState<ImageBitmap?>(
                    initialValue = null,
                    manager,
                    image.id,
                    image.storageKey,
                    image.fileRevision,
                ) {
                    value = withContext(Dispatchers.IO) {
                        decodeUserImageBitmap(manager, image)
                    }
                }
                // Compose's delegated state property cannot be smart-cast across reads.
                val loadedBitmap = bitmap
                if (loadedBitmap != null) {
                    Image(
                        bitmap = loadedBitmap,
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
    return decodeBoundedImageBytes(bytes)
}

// 140dp gallery and 96dp catalog thumbnails do not need full-resolution bitmaps.
// The 10 MiB on-disk limit alone does not bound decoded pixel memory.
private const val MAX_DISPLAY_IMAGE_EDGE_PX = 512

internal fun decodeBoundedImageBytes(bytes: ByteArray): ImageBitmap? {
    // Even Robolectric's bitmap shadow may fabricate a bitmap for arbitrary short
    // bytes. No supported encoded image can fit in fewer than eight bytes.
    if (bytes.size < 8) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // Powers of two are supported by BitmapFactory; compute without overflow even
    // for a malicious image header advertising very large pixel dimensions.
    var sampleSize = 1
    val longestEdge = maxOf(bounds.outWidth, bounds.outHeight).toLong()
    while (longestEdge > MAX_DISPLAY_IMAGE_EDGE_PX.toLong() * sampleSize) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Fail closed if a decoder ignores sampling rather than rendering a huge bitmap.
    if (bitmap.width > MAX_DISPLAY_IMAGE_EDGE_PX || bitmap.height > MAX_DISPLAY_IMAGE_EDGE_PX) {
        bitmap.recycle()
        return null
    }
    return bitmap.asImageBitmap()
}
