package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ExerciseImageItem(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val sourceType: String,
    val storageKey: String,
    val localFile: File?,
)

class ExerciseImageManager internal constructor(
    context: Context,
    private val exerciseDao: ExerciseDao,
    private val afterUserImageSync: (File) -> Unit = {},
    // Test seam for metadata durability after publishing a user image.
    private val syncImageDirectory: (File) -> Unit = ::syncImageDirectoryMetadata,
    // Test seam for controlled deletion failures after Room has removed the image row.
    private val removeUserImageFile: (File) -> Boolean = File::delete,
    // Deterministic seam: an error/cancellation can happen after Room accepted the new row.
    private val afterImageRowInsert: suspend (ExerciseImageEntity) -> Unit = {},
    // Production supplies the AppContainer-wide barrier; tests may omit it.
    private val domainWriteBarrier: BackupDomainWriteBarrier? = null,
) {
    private val imageDirectory = File(context.applicationContext.filesDir, DIRECTORY)

    fun observeImages(exerciseId: String): Flow<List<ExerciseImageItem>> =
        exerciseDao.observeImages(exerciseId).map { images -> images.map(::toItem) }

    suspend fun addUserImage(exerciseId: String, bytes: ByteArray): ExerciseImageItem =
        domainWriteBarrier?.withDomainWrite { addUserImageUnprotected(exerciseId, bytes) }
            ?: addUserImageUnprotected(exerciseId, bytes)

    private suspend fun addUserImageUnprotected(exerciseId: String, bytes: ByteArray): ExerciseImageItem {
        require(bytes.isNotEmpty()) { "La imagen está vacía" }
        require(bytes.size <= MAX_IMAGE_BYTES) { "La imagen supera el límite de 10 MiB" }

        val existing = exerciseDao.getImages(exerciseId)
        require(existing.size < MAX_IMAGES_PER_EXERCISE) {
            "Cada ejercicio admite un máximo de $MAX_IMAGES_PER_EXERCISE imágenes"
        }
        val position = (1..MAX_IMAGES_PER_EXERCISE).first { candidate ->
            existing.none { it.position == candidate }
        }
        // A linked image root (including a dangling link) must never redirect writes
        // outside the app's private image directory.
        check(!Files.isSymbolicLink(imageDirectory.toPath()) &&
            (!imageDirectory.exists() || imageDirectory.isDirectory)) {
            "El directorio de imágenes no es seguro"
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
                afterImageRowInsert(entity)
            } catch (error: Throwable) {
                // A suspended insert can fail/cancel after Room has published its row.
                // Never remove the file while a Room reference might still exist.
                // Compensate in NonCancellable, including errors thrown after insertion.
                withContext(NonCancellable) {
                    var safeToRemoveFile = false
                    val rowLookup = runCatching { exerciseDao.getImageById(id) }
                        .onFailure(error::addSuppressed)
                    if (rowLookup.isSuccess) {
                        val insertedRow = rowLookup.getOrNull()
                        if (insertedRow == null) {
                            safeToRemoveFile = true
                        } else {
                            val removal = runCatching { exerciseDao.deleteImage(insertedRow) }
                                .onFailure(error::addSuppressed)
                            if (removal.isSuccess) {
                                val verification = runCatching { exerciseDao.getImageById(id) == null }
                                    .onFailure(error::addSuppressed)
                                safeToRemoveFile = verification.getOrDefault(false)
                            }
                        }
                    }
                    if (safeToRemoveFile && finalFile.exists()) {
                        if (!finalFile.delete()) {
                            error.addSuppressed(IllegalStateException("No se pudo retirar la imagen sin registrar"))
                        } else {
                            runCatching { syncImageDirectory(imageDirectory) }.onFailure(error::addSuppressed)
                        }
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
        if (domainWriteBarrier == null) {
            deleteUserImageUnprotected(imageId)
        } else {
            domainWriteBarrier.withDomainWrite { deleteUserImageUnprotected(imageId) }
        }
    }

    private suspend fun deleteUserImageUnprotected(imageId: String) {
        val image = requireNotNull(exerciseDao.getImageById(imageId)) { "La imagen ya no existe" }
        require(image.sourceType == USER_SOURCE) { "Solo pueden eliminarse imágenes personalizadas" }
        // Validate the persisted key and on-disk type BEFORE removing the Room row.
        // A corrupt reference must not escape the private directory or silently drop
        // metadata for a directory or dangling symlink (File.exists follows symlinks).
        val file = validatedUserImageFile(image.storageKey)
        // Keep the file until Room confirms the deletion. If removing the file then
        // fails, restore the original Room reference instead of silently orphaning it.
        // This is only exception recovery; abrupt process death still needs the
        // journal/startup protocol and a shared domain write barrier (Issue #56).
        exerciseDao.deleteImage(image)
        val failure = try {
            if (file.exists()) {
                when {
                    !removeUserImageFile(file) -> IOException("No se pudo eliminar el archivo de imagen")
                    file.exists() -> IOException("El archivo de imagen sigue presente tras el borrado")
                    else -> {
                        // Persist the directory removal before reporting a successful deletion.
                        // If fsync fails, bytes may already be gone: never recreate a dangling Room row.
                        syncImageDirectory(imageDirectory)
                        null
                    }
                }
            } else {
                null
            }
        } catch (error: Throwable) {
            error
        }
        if (failure != null) {
            withContext(NonCancellable) {
                // A deletion hook can remove the bytes and then throw (for example, after
                // an I/O side effect). Never recreate a Room reference to a missing file.
                // If the bytes still exist, compensate the metadata as before.
                if (file.isFile) {
                    runCatching { exerciseDao.insertImage(image) }.onFailure(failure::addSuppressed)
                }
            }
            throw failure
        }
    }

    private fun validatedUserImageFile(storageKey: String): File {
        require(!Files.isSymbolicLink(imageDirectory.toPath())) {
            "El directorio de imágenes es un enlace simbólico"
        }
        require(storageKey.startsWith("user:")) { "Referencia de imagen personalizada no válida" }
        val name = storageKey.removePrefix("user:")
        require(
            name.isNotBlank() && name != "." && name != ".." &&
                name.none { it == '/' || it == '\\' || it == '\u0000' },
        ) { "Ruta de imagen personalizada no válida" }
        val file = File(imageDirectory, name)
        require(!Files.isSymbolicLink(file.toPath()) && (!file.exists() || file.isFile)) {
            "La imagen personalizada no es un archivo regular"
        }
        return file
    }

    fun readBytes(item: ExerciseImageItem): ByteArray? {
        if (item.sourceType != USER_SOURCE) return null
        val file = safeReadableUserImageFile(item.storageKey) ?: return null
        // Never trust a caller-supplied File, even when the storage key is valid.
        if (item.localFile?.absolutePath != file.absolutePath) return null
        return file.readBytes()
    }

    private fun toItem(entity: ExerciseImageEntity): ExerciseImageItem =
        ExerciseImageItem(
            id = entity.id,
            exerciseId = entity.exerciseId,
            position = entity.position,
            sourceType = entity.sourceType,
            storageKey = entity.storageKey,
            localFile = if (entity.sourceType == USER_SOURCE) {
                safeReadableUserImageFile(entity.storageKey)
            } else null,
        )

    /** Malformed Room references must not expose paths or linked files to the gallery. */
    private fun safeReadableUserImageFile(storageKey: String): File? {
        if (!storageKey.startsWith("user:")) return null
        val name = storageKey.removePrefix("user:")
        if (name.isBlank() || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\\\' || it == '\\u0000' }) return null
        if (Files.isSymbolicLink(imageDirectory.toPath()) || !imageDirectory.isDirectory) return null
        val file = File(imageDirectory, name)
        return file.takeIf { !Files.isSymbolicLink(it.toPath()) && it.isFile }
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
