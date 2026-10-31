package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Persistent pre-image for an interrupted replace-all. This component only manages the journal:
 * startup recovery and the Room/files/DataStore mutation protocol must be wired separately.
 *
 * An unknown, incomplete or corrupt journal fails closed. In particular, no cleanup is attempted
 * on PREPARED data. The state file is replaced via a same-directory rename; power-loss durability
 * of directory metadata still needs verification on the supported Android filesystems.
 */
internal class BackupRecoveryJournal(
    private val directory: File,
    // Deterministic test seam: replace a validated file before its descriptor is opened.
    private val beforeTrustedRead: (File) -> Unit = {},
    // Deterministic seam: swap a validated temporary file before its descriptor is opened.
    private val beforeTrustedWrite: (File) -> Unit = {},
    // Deterministic seam: replace a synced temporary entry before its publication rename.
    private val beforeTrustedPublish: (File) -> Unit = {},
    // Deterministic seam: replace the active root after inspect() but before deleting state.tmp.
    private val beforeInterruptedCommitCleanup: (File) -> Unit = {},
    // Injected by tests to verify ordering/fail-closed behavior; production syncs metadata.
    private val syncDirectory: (File) -> Unit = ::syncDirectoryMetadata,
    // Deterministic seam: replace a retired/preparing root after preflight, before deletion.
    private val beforeArtifactDelete: (File) -> Unit = {},
) {
    /** Stable in-process identity shared by coordinator instances for this journal path. */
    internal val lockKey: String = directory.canonicalPath
    // Retire COMMITTED state before deleting files: a crash must not leave a partial active journal.
    private val retiredDirectory = File(directory.parentFile, "${directory.name}.retired")
    // PREPARED is published only after both files are synced; a crash during staging cannot
    // create an incomplete active journal that would permanently block the next startup.
    private val preparingDirectory = File(directory.parentFile, "${directory.name}.preparing")
    sealed interface State {
        data object Absent : State
        data class Prepared(val previousArchive: ByteArray) : State
        data object Committed : State
    }

    /** Fail closed on unsafe recovery artifacts before best-effort cleanup can suppress errors. */
    internal fun verifyPathSafety() {
        requireSafeJournalParent()
        requireNotSymlink(directory)
        requireNotSymlink(preparingDirectory)
        requireNotSymlink(retiredDirectory)
    }

    fun inspect(): State {
        requireSafeJournalParent()
        requireNotSymlink(directory)
        if (!directory.exists()) return State.Absent
        check(directory.isDirectory) { "El journal no es un directorio" }
        val stateFile = File(directory, STATE_FILE)
        val parts = readTrustedFile(stateFile, MAX_STATE_BYTES)
            .toString(Charsets.US_ASCII).trimEnd('\n').split(' ')
        check(parts.size == 3 && parts[0] == VERSION && parts[2].matches(SHA256)) {
            "Journal de recuperación desconocido o corrupto"
        }
        check(parts[1] == "PREPARED" || parts[1] == "COMMITTED") {
            "Estado de recuperación desconocido"
        }
        val archiveFile = File(directory, ARCHIVE_FILE)
        val archive = readTrustedFile(archiveFile, MAX_ARCHIVE_BYTES)
        check(sha256(archive) == parts[2]) { "Checksum de recuperación incorrecto" }
        return if (parts[1] == "COMMITTED") State.Committed else State.Prepared(archive)
    }

    fun prepare(previousArchive: ByteArray) {
        // mkdirs() must never create staging through a linked private parent.
        requireSafeJournalParent()
        requireNotSymlink(directory)
        requireNotSymlink(retiredDirectory)
        requireNotSymlink(preparingDirectory)
        require(previousArchive.isNotEmpty() && previousArchive.size.toLong() <= MAX_ARCHIVE_BYTES) {
            "Tamaño de copia previa no válido"
        }
        check(!directory.exists()) { "Ya existe un journal de recuperación: resolver antes de importar" }
        check(!retiredDirectory.exists()) { "Queda una limpieza anterior pendiente" }
        check(!preparingDirectory.exists()) { "Queda una preparación incompleta pendiente" }
        check(preparingDirectory.mkdirs()) { "No se pudo crear la preparación del journal" }
        syncDirectory(preparingDirectory.parentFile)
        // An interrupted preparation is not an active journal: no domain mutation is allowed
        // until this method publishes the complete PREPARED directory and returns.
        writeSynced(File(preparingDirectory, ARCHIVE_FILE), previousArchive)
        val digest = sha256(previousArchive)
        writeSynced(File(preparingDirectory, STATE_FILE), "$VERSION PREPARED $digest\n".toByteArray(Charsets.US_ASCII))
        // Directory fsync can be followed by a root swap before publication. Never
        // promote a symlink (or replace an unexpected active entry) as PREPARED.
        requireSafeJournalDirectory(preparingDirectory)
        requireNotSymlink(directory)
        check(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Apareció un journal activo durante la preparación"
        }
        check(preparingDirectory.renameTo(directory)) { "No se pudo publicar el journal preparado" }
        // Persist the PREPARED directory entry before any caller can mutate domain data.
        syncDirectory(directory.parentFile)
    }

    fun markCommitted() {
        val prepared = inspect()
        check(prepared is State.Prepared) { "No hay recuperación preparada que confirmar" }
        // A process may die after syncing state.tmp but before renaming it over PREPARED.
        // PREPARED is still authoritative; discard only this uncommitted marker so startup
        // recovery can finish instead of being permanently blocked by the stale temp file.
        val interruptedCommit = File(directory, "$STATE_FILE.tmp")
        beforeInterruptedCommitCleanup(interruptedCommit)
        // inspect() validated the root before this point, but it may have been swapped
        // while the marker was being examined. Never delete state.tmp through a
        // linked intermediate directory, even when the final file is regular.
        requireSafeJournalDirectory(directory)
        requireNotSymlink(interruptedCommit)
        if (Files.exists(interruptedCommit.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(interruptedCommit.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Temporal de confirmación del journal inseguro"
            }
            requireSafeJournalDirectory(directory)
            requireNotSymlink(interruptedCommit)
            check(interruptedCommit.delete()) {
                "No se pudo limpiar un marcador COMMITTED interrumpido"
            }
        }
        val digest = sha256(prepared.previousArchive)
        writeSynced(File(directory, STATE_FILE), "$VERSION COMMITTED $digest\n".toByteArray(Charsets.US_ASCII), replace = true)
    }

    /**
     * Move the complete COMMITTED journal aside before deleting any contents. A process death
     * during deletion leaves only retired garbage, not a half-deleted active journal.
     */
    fun cleanupCommitted() {
        check(inspect() is State.Committed) { "No se puede eliminar un journal pendiente" }
        requireNotSymlink(retiredDirectory)
        check(!retiredDirectory.exists()) { "Queda una limpieza anterior pendiente" }
        requireSafeJournalDirectory(directory)
        check(directory.renameTo(retiredDirectory)) { "No se pudo retirar el journal confirmado" }
        syncDirectory(directory.parentFile)
        cleanupRetired()
    }

    /** Discard only an unpublished pre-image: no data mutation can have started yet. */
    fun cleanupAbandonedPreparation() {
        requireNotSymlink(preparingDirectory)
        if (!preparingDirectory.exists()) return
        check(!directory.exists()) { "No se descarta preparación si existe un journal activo" }
        cleanupJournalArtifacts(preparingDirectory)
    }

    /** Retry cleanup of a previously COMMITTED journal after an interrupted deletion. */
    fun cleanupRetired() {
        requireNotSymlink(retiredDirectory)
        if (retiredDirectory.exists()) {
            cleanupJournalArtifacts(retiredDirectory)
        }
    }

    /**
     * Delete only our flat journal artifacts. File.deleteRecursively() can traverse directory
     * symlinks; a symlink nested inside a retired or unpublished journal must never lead to
     * deletion outside the private recovery area. Preflight the entire directory before deleting
     * any entry, and refuse unexpected files or subdirectories rather than guessing ownership.
     * This is still not an atomic defence against a concurrent filesystem attacker (TOCTOU).
     */
    private fun cleanupJournalArtifacts(path: File) {
        requireSafeJournalDirectory(path)
        requireNotSymlink(path)
        check(path.isDirectory) { "La ruta de limpieza no es un directorio de journal" }
        val entries = path.listFiles() ?: error("No se pudo inspeccionar el directorio de journal")
        val allowed = setOf(ARCHIVE_FILE, STATE_FILE, "$ARCHIVE_FILE.tmp", "$STATE_FILE.tmp")
        entries.forEach { entry ->
            requireNotSymlink(entry)
            check(entry.name in allowed && entry.isFile) {
                "Contenido inesperado en directorio de journal: ${entry.name}"
            }
        }
        entries.forEach { entry ->
            // The root or a preflighted entry can change before the destructive operation.
            beforeArtifactDelete(entry)
            requireSafeJournalDirectory(path)
            requireNotSymlink(entry)
            check(Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Artefacto de journal inseguro antes del borrado: ${entry.name}"
            }
            // Path checks narrow deterministic swaps, but do not provide atomic TOCTOU safety.
            check(entry.delete()) { "No se pudo eliminar el archivo de journal: ${entry.name}" }
        }
        requireSafeJournalDirectory(path)
        check(path.delete()) { "No se pudo eliminar el directorio de journal" }
        syncDirectory(path.parentFile)
    }

    /**
     * The final component must still be a regular file when its descriptor opens.
     * Checking File.isFile / isSymbolicLink then calling readBytes would allow a
     * symlink substitution between those operations. NOFOLLOW_LINKS is enforced
     * by the descriptor open itself; reads are bounded even if the file grows.
     * Ancestor directory substitution is a separate unresolved TOCTOU risk.
     */
    private fun readTrustedFile(file: File, maxBytes: Long): ByteArray {
        requireNotSymlink(file)
        check(file.isFile && file.length() in 1..maxBytes) {
            "Archivo de recuperación ausente o de tamaño inválido: ${file.name}"
        }
        beforeTrustedRead(file)
        // NOFOLLOW_LINKS on the final file does not protect an intermediate journal root.
        requireSafeJournalDirectory(file.parentFile)
        Files.newByteChannel(
            file.toPath(),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val expectedSize = channel.size()
            check(expectedSize in 1..maxBytes) {
                "Archivo de recuperación de tamaño inválido: ${file.name}"
            }
            val bytes = Channels.newInputStream(channel).readNBytes(expectedSize.toInt() + 1)
            check(bytes.size.toLong() == expectedSize && channel.size() == expectedSize) {
                "El archivo de recuperación cambió durante la lectura: ${file.name}"
            }
            return bytes
        }
    }

    private fun writeSynced(target: File, bytes: ByteArray, replace: Boolean = false) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        requireNotSymlink(target)
        requireNotSymlink(temp)
        check(!temp.exists()) { "Existe una escritura incompleta del journal" }
        // CREATE_NEW plus NOFOLLOW_LINKS prevents a symlink injected after preflight
        // from redirecting journal bytes to another file. Never truncate an existing temp.
        beforeTrustedWrite(temp)
        // Recheck after test hooks and before opening: the journal directory itself
        // may have been replaced by a symlink while the final temp name remains safe.
        requireSafeJournalDirectory(temp.parentFile)
        FileChannel.open(
            temp.toPath(),
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            Channels.newOutputStream(channel).write(bytes)
            channel.force(true)
        }
        // A synced temporary entry can be swapped before its rename. NOFOLLOW_LINKS
        // protected the write descriptor, but not the later path-based publication.
        // Reject a linked temp, a swapped journal root, or a linked target instead
        // of promoting an untrusted entry into PREPARED/COMMITTED.
        beforeTrustedPublish(temp)
        requireSafeJournalDirectory(temp.parentFile)
        check(Files.isRegularFile(temp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Temporal de journal inseguro antes de publicarlo: ${temp.name}"
        }
        // A regular file can replace the synced temporary entry just as a symlink can.
        // Verify its bytes through a NOFOLLOW descriptor before publishing the journal.
        // This narrows deterministic substitution; path-based rename remains non-atomic.
        requireSyncedTemporaryContent(temp, bytes)
        requireNotSymlink(target)
        if (replace) {
            // Do not remove PREPARED before COMMITTED is ready: keep the old marker if rename fails.
            check(temp.renameTo(target)) { "No se pudo confirmar el journal" }
        } else {
            check(!target.exists() && temp.renameTo(target)) { "No se pudo publicar el journal" }
        }
        // fsync(file) alone does not persist the rename/creation of its directory entry.
        syncDirectory(target.parentFile)
    }

    /**
     * A regular-file replacement after fsync must not publish a different pre-image or marker.
     * Stream the check so a maximum-sized backup does not require a second in-memory copy.
     * The check and subsequent rename are not an atomic filesystem operation.
     */
    private fun requireSyncedTemporaryContent(temp: File, expected: ByteArray) {
        requireSafeJournalDirectory(temp.parentFile)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expected)
        val actualDigest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(
            temp.toPath(),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            check(channel.size() == expected.size.toLong()) {
                "Temporal de journal alterado antes de publicarlo: ${temp.name}"
            }
            val input = Channels.newInputStream(channel)
            val buffer = ByteArray(64 * 1024)
            var remaining = expected.size.toLong()
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(read > 0) { "Temporal de journal truncado: ${temp.name}" }
                actualDigest.update(buffer, 0, read)
                remaining -= read
            }
            check(channel.size() == expected.size.toLong() &&
                actualDigest.digest().contentEquals(expectedDigest)
            ) {
                "Temporal de journal modificado antes de publicarlo: ${temp.name}"
            }
        }
    }

    /**
     * Reject a linked private parent or journal subdirectory before accessing bytes.
     * This narrows deterministic directory swaps; path checks are not an atomic
     * defence against concurrent ancestor replacement (TOCTOU).
     */
    private fun requireSafeJournalParent() {
        check(Files.isDirectory(directory.parentFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Directorio privado padre de journal inseguro"
        }
    }

    private fun requireSafeJournalDirectory(path: File) {
        check(Files.isDirectory(path.parentFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Directorio padre de journal inseguro"
        }
        check(Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Directorio de journal inseguro: ${path.name}"
        }
    }

    /** Reject even dangling links: File.exists() follows links and can hide them. */
    private fun requireNotSymlink(file: File) {
        check(!Files.isSymbolicLink(file.toPath())) { "Ruta de journal con enlace simbólico: ${file.name}" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Android 15+ supports NIO FileChannel; fail closed if directory fsync is unavailable. */
        fun syncDirectoryMetadata(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }

        const val VERSION = "1"
        const val ARCHIVE_FILE = "previous.zip"
        const val STATE_FILE = "state"
        const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
        const val MAX_STATE_BYTES = 128L
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
