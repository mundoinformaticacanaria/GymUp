package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import android.net.Uri

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
            input.readBytes()
        }

    fun defaultFileName(): String = "gymup-backup.zip"
}
