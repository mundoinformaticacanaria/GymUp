package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ExerciseImageItem(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val sourceType: String,
    val storageKey: String,
    val localFile: File?,
)

class ExerciseImageManager(
    context: Context,
    private val exerciseDao: ExerciseDao,
    private val afterUserImageSync: (File) -> Unit = {},
    // Test seam for metadata durability after publishing a user image.
    private val syncImageDirectory: (File) -> Unit = ::syncImageDirectoryMetadata,
) {
    private val imageDirectory = File(context.applicationContext.filesDir, DIRECTORY)

    fun observeImages(exerciseId: String): Flow<List<ExerciseImageItem>> =
        exerciseDao.observeImages(exerciseId).map { images -> images.map(::toItem) }

    suspend fun addUserImage(exerciseId: String, bytes: ByteArray): ExerciseImageItem {
        require(bytes.isNotEmpty()) { "La imagen está vacía" }
        require(bytes.size <= MAX_IMAGE_BYTES) { "La imagen supera el límite de 10 MiB" }

        val existing = exerciseDao.getImages(exerciseId)
        require(existing.size < MAX_IMAGES_PER_EXERCISE) {
            "Cada ejercicio admite un máximo de $MAX_IMAGES_PER_EXERCISE imágenes"
        }
        val position = (1..MAX_IMAGES_PER_EXERCISE).first { candidate ->
            existing.none { it.position == candidate }
        }
        val createdDirectory = !imageDirectory.exists()
        check(imageDirectory.exists() || imageDirectory.mkdirs()) {
            "No se pudo preparar el almacenamiento de imágenes"
        }
        // The directory itself must survive a restart before Room can refer to its files.
        if (createdDirectory) syncImageDirectory(imageDirectory.parentFile)

        val id = UUID.randomUUID().toString()
        val storageKey = "user:$id"
        val finalFile = fileFor(storageKey)
        val temporaryFile = File(imageDirectory, ".$id.tmp")
        try {
            // Persist the image bytes before publishing the filename and Room metadata.
            // Directory rename durability still requires separate Android validation.
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporaryFile.length() == bytes.size.toLong()) { "No se pudo guardar la imagen" }
            // Deterministic test seam: failure here must leave neither a published file nor Room metadata.
            afterUserImageSync(temporaryFile)
            check(temporaryFile.renameTo(finalFile)) { "No se pudo activar la imagen guardada" }
            val entity = ExerciseImageEntity(
                id = id,
                exerciseId = exerciseId,
                position = position,
                sourceType = USER_SOURCE,
                storageKey = storageKey,
                originalSourceUrl = null,
                author = null,
                license = null,
            )
            try {
                // Persist the rename before publishing the Room reference. A file fsync
                // alone cannot guarantee the new directory entry survives a crash.
                syncImageDirectory(imageDirectory)
                exerciseDao.insertImage(entity)
            } catch (error: Throwable) {
                // The DB reference was not published if directory sync failed; remove
                // the unreferenced file and try to persist that cleanup as well.
                if (finalFile.exists()) {
                    if (!finalFile.delete()) {
                        error.addSuppressed(IllegalStateException("No se pudo retirar la imagen sin registrar"))
                    } else {
                        runCatching { syncImageDirectory(imageDirectory) }.onFailure(error::addSuppressed)
                    }
                }
                throw error
            }
            return toItem(entity)
        } finally {
            temporaryFile.delete()
        }
    }

    suspend fun deleteUserImage(imageId: String) {
        val image = requireNotNull(exerciseDao.getImageById(imageId)) { "La imagen ya no existe" }
        require(image.sourceType == USER_SOURCE) { "Solo pueden eliminarse imágenes personalizadas" }
        exerciseDao.deleteImage(image)
        fileFor(image.storageKey).delete()
    }

    fun readBytes(item: ExerciseImageItem): ByteArray? =
        item.localFile?.takeIf(File::isFile)?.readBytes()

    private fun toItem(entity: ExerciseImageEntity): ExerciseImageItem {
        val file = if (entity.sourceType == USER_SOURCE) fileFor(entity.storageKey) else null
        return ExerciseImageItem(
            id = entity.id,
            exerciseId = entity.exerciseId,
            position = entity.position,
            sourceType = entity.sourceType,
            storageKey = entity.storageKey,
            localFile = file?.takeIf(File::isFile),
        )
    }

    private fun fileFor(storageKey: String): File =
        File(imageDirectory, storageKey.removePrefix("user:"))

    companion object {
        private fun syncImageDirectoryMetadata(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        const val MAX_IMAGES_PER_EXERCISE = 3
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private const val DIRECTORY = "exercise-images"
        private const val USER_SOURCE = "USER"
    }
}
