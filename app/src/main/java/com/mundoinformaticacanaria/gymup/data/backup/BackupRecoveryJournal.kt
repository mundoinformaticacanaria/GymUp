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
        check(directory.mkdirs()) { "No se pudo crear el journal de recuperación" }
        // If writing fails, leave the directory in place to force explicit recovery/inspection.
        writeSynced(File(directory, ARCHIVE_FILE), previousArchive)
        val digest = sha256(previousArchive)
        writeSynced(File(directory, STATE_FILE), "$VERSION PREPARED $digest\n".toByteArray(Charsets.US_ASCII))
    }

    fun markCommitted() {
        val prepared = inspect()
        check(prepared is State.Prepared) { "No hay recuperación preparada que confirmar" }
        val digest = sha256(prepared.previousArchive)
        writeSynced(File(directory, STATE_FILE), "$VERSION COMMITTED $digest\n".toByteArray(Charsets.US_ASCII), replace = true)
    }

    /** Only committed journals can be discarded; a failed deletion remains retryable. */
    fun cleanupCommitted() {
        check(inspect() is State.Committed) { "No se puede eliminar un journal pendiente" }
        check(directory.deleteRecursively()) { "No se pudo limpiar el journal confirmado" }
    }

    private fun writeSynced(target: File, bytes: ByteArray, replace: Boolean = false) {
        val temp = File(directory, "${target.name}.tmp")
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
