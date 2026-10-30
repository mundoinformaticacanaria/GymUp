package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Read-only diagnostic for USER image references stored in Room.
 *
 * Unlike orphan reconciliation, this walks the references, including when the active
 * image directory is missing. It never repairs, deletes or blocks startup on its own.
 * A caller must hold the backup domain barrier and resolve the recovery journal first.
 *
 * This is intentionally NOT wired into BackupStartupGate. It does not establish byte
 * integrity, prevent concurrent path substitution or decide whether missing historical
 * images should block access to the app.
 */
internal class UserImageReferenceIntegrityInspector(
    context: Context,
    private val exerciseDao: ExerciseDao,
) {
    private val directory = File(context.applicationContext.filesDir, "exercise-images")

    suspend fun missingOrUnsafeStorageKeys(): Set<String> {
        val referenced = exerciseDao.getAllUserImageStorageKeys().toSet()
        if (referenced.isEmpty()) return emptySet()

        val root = directory.toPath()
        if (Files.isSymbolicLink(root)) throw IOException("Directorio de imágenes enlazado")
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return referenced
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("La ruta de imágenes no es un directorio")
        }

        return referenced.filterTo(linkedSetOf()) { key ->
            if (!key.startsWith("user:")) return@filterTo true
            val filename = key.removePrefix("user:")
            if (!USER_FILENAME.matches(filename)) return@filterTo true
            val path = directory.resolve(filename).toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@filterTo true
            try {
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                    val size = it.size()
                    size <= 0L || size > MAX_IMAGE_BYTES
                }
            } catch (_: IOException) {
                true
            }
        }
    }

    private companion object {
        // Same filename alphabet accepted by ZIP v1; compare names exactly, without renaming.
        val USER_FILENAME = Regex("^[0-9a-fA-F-]{36}$")
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    }
}
