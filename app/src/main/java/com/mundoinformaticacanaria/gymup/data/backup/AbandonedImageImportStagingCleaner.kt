package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Conservative, opt-in cleanup of unpublished image import staging directories (#56).
 *
 * NOT wired into startup: physical crash/durability gates must be completed first.
 * The caller must have resolved the backup journal and hold the exclusive domain
 * write barrier. Never use this to remove exercise-images-previous-*; those directories
 * can be the last recoverable copy after an interrupted directory swap.
 *
 * A candidate is removed only if the active image root exists, is a safe flat directory,
 * and contains every referenced USER image as a nonempty regular file. This is a
 * structural check, NOT a proof of byte-level integrity or power-loss durability.
 * Image names use the same legacy-safe character set accepted by ZIP v1
 * (36 ASCII hex/hyphen characters, including upper-case and noncanonical UUIDs).
 * This is a filename-safety check, not UUID validity or byte-integrity proof.
 * Unknown names, links and nested directories are preserved without traversal.
 */
internal class AbandonedImageImportStagingCleaner(
    private val filesDir: File,
    private val syncParent: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    },
) {
    data class Result(val removed: Int, val preserved: Int)

    fun cleanupAfterRecovery(referencedStorageKeys: Set<String>): Result {
        val parent = filesDir.toPath()
        check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "El directorio privado de la aplicación no es seguro"
        }
        val candidates = filesDir.listFiles()
            ?.filter { STAGING_DIRECTORY.matches(it.name) }
            ?: error("No se pudieron enumerar los directorios privados")

        // Fail closed without touching any staging directory if the active root is
        // absent, malformed or missing a reference. This includes the crash window
        // after moving the old root aside but before publishing staging.
        val root = File(filesDir, "exercise-images")
        if (!isSafeFlatDirectory(root) || referencedStorageKeys.any { key ->
                !USER_KEY.matches(key) || !isNonemptyRegularFile(File(root, key.removePrefix("user:")))
            }
        ) {
            return Result(removed = 0, preserved = candidates.size)
        }

        var removed = 0
        var preserved = 0
        for (candidate in candidates) {
            if (!isSafeFlatDirectory(candidate)) {
                preserved++
                continue
            }
            // No recursive traversal, no symlink following, and no wildcard deletion.
            // A failed delete is surfaced so a later controlled attempt can retry.
            Files.newDirectoryStream(candidate.toPath()).use { stream ->
                stream.forEach { Files.delete(it) }
            }
            Files.delete(candidate.toPath())
            syncParent(filesDir)
            removed++
        }
        return Result(removed, preserved)
    }

    private fun isSafeFlatDirectory(directory: File): Boolean {
        val path = directory.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            Files.newDirectoryStream(path).use { stream ->
                stream.all { entry ->
                    IMAGE_FILE.matches(entry.fileName.toString()) &&
                        isNonemptyRegularFile(entry.toFile())
                }
            }
        }.getOrDefault(false)
    }

    private fun isNonemptyRegularFile(file: File): Boolean {
        val path = file.toPath()
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) in 1..MAX_IMAGE_BYTES }.getOrDefault(false)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        // BackupDataCodec and UserExerciseImageStore accept legacy noncanonical USER keys.
        // Keep parity so valid imported images do not indefinitely retain staging.
        // Slash, backslash, dot and all other path-control characters remain excluded.
        val IMAGE_FILE = Regex("^[0-9a-fA-F-]{36}$")
        val USER_KEY = Regex("^user:[0-9a-fA-F-]{36}$")
        val STAGING_DIRECTORY = Regex("^exercise-images-import-$UUID_PATTERN$")
    }
}
