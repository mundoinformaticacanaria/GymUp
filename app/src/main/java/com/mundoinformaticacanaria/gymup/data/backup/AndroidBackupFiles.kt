package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import android.net.Uri
import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
import java.io.InputStream

object AndroidBackupFiles {
    const val MIME_TYPE = "application/zip"

    fun writeToDocument(context: Context, destination: Uri, archive: ByteArray) {
        context.contentResolver.openOutputStream(destination, "w").use { output ->
            requireNotNull(output) { "No se pudo abrir el destino del backup" }
            output.write(archive)
        }
    }

    fun readFromDocument(context: Context, source: Uri): ByteArray =
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "No se pudo abrir el backup" }
            readBoundedBackupDocument(input)
        }

    fun defaultFileName(): String = "gymup-backup.zip"
}

/** SAF streams are untrusted; bound the copy before parsing or allocating the ZIP. */
internal fun readBoundedBackupDocument(
    input: InputStream,
    maxBytes: Int = BackupArchiveCodec.MAX_ARCHIVE_BYTES.toInt(),
): ByteArray {
    require(maxBytes > 0 && maxBytes < Int.MAX_VALUE) { "Límite de backup no válido" }
    // Read one extra byte to reject oversized providers without draining the stream.
    val bytes = input.readNBytes(maxBytes + 1)
    require(bytes.size <= maxBytes) { "El backup supera el tamaño máximo permitido" }
    return bytes
}
