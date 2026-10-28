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
    // Injected by tests to verify ordering/fail-closed behavior; production syncs metadata.
    private val syncDirectory: (File) -> Unit = ::syncDirectoryMetadata,
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
        requireNotSymlink(directory)
        requireNotSymlink(preparingDirectory)
        requireNotSymlink(retiredDirectory)
    }

    fun inspect(): State {
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
        requireNotSymlink(interruptedCommit)
        if (interruptedCommit.exists()) {
            check(interruptedCommit.isFile && interruptedCommit.delete()) {
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
            check(entry.delete()) { "No se pudo eliminar el archivo de journal: ${entry.name}" }
        }
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
        FileChannel.open(
            temp.toPath(),
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            Channels.newOutputStream(channel).write(bytes)
            channel.force(true)
        }
        if (replace) {
            // Do not remove PREPARED before COMMITTED is ready: keep the old marker if rename fails.
            check(temp.renameTo(target)) { "No se pudo confirmar el journal" }
        } else {
            check(!target.exists() && temp.renameTo(target)) { "No se pudo publicar el journal" }
        }
        // fsync(file) alone does not persist the rename/creation of its directory entry.
        syncDirectory(target.parentFile)
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
