package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
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
    // Test seam: replace a preflighted candidate before opening it for deletion.
    private val beforeCandidateDelete: (File) -> Unit = {},
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
            // A candidate can be swapped after the path-based preflight. Deletion must
            // be anchored to an open private-parent descriptor, never to a followed
            // candidate path. If Android does not provide SecureDirectoryStream or
            // stable fileKey identities, fail closed rather than risk external bytes.
            val candidateIdentity = fileKey(candidate)
            beforeCandidateDelete(candidate)
            if (candidateIdentity == null || !isSafeFlatDirectory(candidate) ||
                fileKey(candidate) != candidateIdentity
            ) {
                preserved++
                continue
            }
            deleteViaSecureDirectoryStream(candidate, candidateIdentity)
            syncParent(filesDir)
            removed++
        }
        return Result(removed, preserved)
    }

    /**
     * Relative unlink operations are anchored to open directory handles. A swapped
     * symlink cannot redirect deletion into another directory. This still cannot
     * prove power-loss durability or exclude a malicious same-UID concurrent process.
     */
    private fun deleteViaSecureDirectoryStream(candidate: File, expectedIdentity: Any) {
        val expectedParentIdentity = fileKey(filesDir)
        check(expectedParentIdentity != null) { "Identidad del directorio privado no disponible" }
        Files.newDirectoryStream(filesDir.toPath()).use { parentStream ->
            check(parentStream is SecureDirectoryStream<*>) {
                "El filesystem no admite borrado seguro de staging"
            }
            @Suppress("UNCHECKED_CAST")
            val secureParent = parentStream as SecureDirectoryStream<Path>
            check(secureParent.getFileAttributeView(BasicFileAttributeView::class.java)
                .readAttributes().fileKey() == expectedParentIdentity
            ) { "El directorio privado cambió antes de limpiar staging" }
            val name = candidate.toPath().fileName
            secureParent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { candidateStream ->
                check(candidateStream is SecureDirectoryStream<*>) {
                    "El filesystem no admite borrado seguro del candidato"
                }
                @Suppress("UNCHECKED_CAST")
                val secureCandidate = candidateStream as SecureDirectoryStream<Path>
                check(secureCandidate.getFileAttributeView(BasicFileAttributeView::class.java)
                    .readAttributes().fileKey() == expectedIdentity
                ) { "El staging fue sustituido antes de abrirlo" }
                val entries = secureCandidate.toList()
                // Preflight every entry again through the open directory descriptor
                // before deleting anything; never follow a linked final component.
                check(entries.all { entry ->
                    val attrs = secureCandidate.getFileAttributeView(
                        entry.fileName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                    )?.readAttributes()
                    IMAGE_FILE.matches(entry.fileName.toString()) &&
                        attrs?.isRegularFile == true && attrs.size() in 1..MAX_IMAGE_BYTES
                }) { "Contenido de staging inseguro antes del borrado" }
                entries.forEach { secureCandidate.deleteFile(it.fileName) }
            }
            check(secureParent.getFileAttributeView(
                name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes()?.fileKey() == expectedIdentity
            ) { "El staging cambió antes de retirar el directorio" }
            secureParent.deleteDirectory(name)
        }
    }

    private fun fileKey(file: File): Any? =
        Files.readAttributes(
            file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        ).fileKey()

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
