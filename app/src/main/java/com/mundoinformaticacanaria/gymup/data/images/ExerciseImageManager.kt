package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import java.io.File
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
        check(imageDirectory.exists() || imageDirectory.mkdirs()) {
            "No se pudo preparar el almacenamiento de imágenes"
        }

        val id = UUID.randomUUID().toString()
        val storageKey = "user:$id"
        val finalFile = fileFor(storageKey)
        val temporaryFile = File(imageDirectory, ".$id.tmp")
        try {
            temporaryFile.writeBytes(bytes)
            check(temporaryFile.length() == bytes.size.toLong()) { "No se pudo guardar la imagen" }
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
                exerciseDao.insertImage(entity)
            } catch (error: Throwable) {
                finalFile.delete()
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
        const val MAX_IMAGES_PER_EXERCISE = 3
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private const val DIRECTORY = "exercise-images"
        private const val USER_SOURCE = "USER"
    }
}
