package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Persistent pre-image for an interrupted replace-all. This component only manages the journal:
 * startup recovery and the Room/files/DataStore mutation protocol must be wired separately.
 *
 * An unknown, incomplete or corrupt journal fails closed. In particular, no cleanup is attempted
 * on PREPARED data. The state file is replaced via a same-directory rename; power-loss durability
 * of directory metadata still needs verification on the supported Android filesystems.
 */
internal class BackupRecoveryJournal(private val directory: File) {
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

    fun inspect(): State {
        if (!directory.exists()) return State.Absent
        check(directory.isDirectory) { "El journal no es un directorio" }
        val stateFile = File(directory, STATE_FILE)
        check(stateFile.isFile && stateFile.length() in 1..MAX_STATE_BYTES) { "Journal incompleto o marcador de tamaño inválido" }
        val parts = stateFile.readText(Charsets.US_ASCII).trimEnd('\n').split(' ')
        check(parts.size == 3 && parts[0] == VERSION && parts[2].matches(SHA256)) {
            "Journal de recuperación desconocido o corrupto"
        }
        if (parts[1] == "COMMITTED") return State.Committed
        check(parts[1] == "PREPARED") { "Estado de recuperación desconocido" }
        val archiveFile = File(directory, ARCHIVE_FILE)
        check(archiveFile.isFile && archiveFile.length() in 1..MAX_ARCHIVE_BYTES) {
            "Falta la copia previa o su tamaño no es válido"
        }
        val archive = archiveFile.readBytes()
        check(sha256(archive) == parts[2]) { "Checksum de recuperación incorrecto" }
        return State.Prepared(archive)
    }

    fun prepare(previousArchive: ByteArray) {
        require(previousArchive.isNotEmpty() && previousArchive.size.toLong() <= MAX_ARCHIVE_BYTES) {
            "Tamaño de copia previa no válido"
        }
        check(!directory.exists()) { "Ya existe un journal de recuperación: resolver antes de importar" }
        check(!retiredDirectory.exists()) { "Queda una limpieza anterior pendiente" }
        check(!preparingDirectory.exists()) { "Queda una preparación incompleta pendiente" }
        check(preparingDirectory.mkdirs()) { "No se pudo crear la preparación del journal" }
        // An interrupted preparation is not an active journal: no domain mutation is allowed
        // until this method publishes the complete PREPARED directory and returns.
        writeSynced(File(preparingDirectory, ARCHIVE_FILE), previousArchive)
        val digest = sha256(previousArchive)
        writeSynced(File(preparingDirectory, STATE_FILE), "$VERSION PREPARED $digest\n".toByteArray(Charsets.US_ASCII))
        check(preparingDirectory.renameTo(directory)) { "No se pudo publicar el journal preparado" }
    }

    fun markCommitted() {
        val prepared = inspect()
        check(prepared is State.Prepared) { "No hay recuperación preparada que confirmar" }
        // A process may die after syncing state.tmp but before renaming it over PREPARED.
        // PREPARED is still authoritative; discard only this uncommitted marker so startup
        // recovery can finish instead of being permanently blocked by the stale temp file.
        val interruptedCommit = File(directory, "$STATE_FILE.tmp")
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
        check(!retiredDirectory.exists()) { "Queda una limpieza anterior pendiente" }
        check(directory.renameTo(retiredDirectory)) { "No se pudo retirar el journal confirmado" }
        cleanupRetired()
    }

    /** Discard only an unpublished pre-image: no data mutation can have started yet. */
    fun cleanupAbandonedPreparation() {
        if (!preparingDirectory.exists()) return
        check(!directory.exists()) { "No se descarta preparación si existe un journal activo" }
        check(preparingDirectory.deleteRecursively()) { "No se pudo limpiar la preparación incompleta" }
    }

    /** Retry cleanup of a previously COMMITTED journal after an interrupted deletion. */
    fun cleanupRetired() {
        if (retiredDirectory.exists()) {
            check(retiredDirectory.deleteRecursively()) { "No se pudo limpiar el journal retirado" }
        }
    }

    private fun writeSynced(target: File, bytes: ByteArray, replace: Boolean = false) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        check(!temp.exists()) { "Existe una escritura incompleta del journal" }
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (replace) {
            // Do not remove PREPARED before COMMITTED is ready: keep the old marker if rename fails.
            check(temp.renameTo(target)) { "No se pudo confirmar el journal" }
        } else {
            check(!target.exists() && temp.renameTo(target)) { "No se pudo publicar el journal" }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val VERSION = "1"
        const val ARCHIVE_FILE = "previous.zip"
        const val STATE_FILE = "state"
        const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
        const val MAX_STATE_BYTES = 128L
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
