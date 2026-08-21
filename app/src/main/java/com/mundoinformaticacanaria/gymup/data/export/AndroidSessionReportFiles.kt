package com.mundoinformaticacanaria.gymup.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Android file integration for the v1 session JSON report. */
object AndroidSessionReportFiles {
    const val MIME_TYPE = "application/json"
    const val PROVIDER_SUFFIX = ".fileprovider"

    fun writeToDocument(
        context: Context,
        destination: Uri,
        json: String,
    ) {
        context.contentResolver.openOutputStream(destination, "w").use { output ->
            requireNotNull(output) { "No se pudo abrir el destino del informe" }
            output.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    fun prepareShare(
        context: Context,
        fileName: String,
        json: String,
    ): Intent {
        val reportsDir = File(context.cacheDir, "session-reports").apply { mkdirs() }
        val file = File(reportsDir, fileName).also {
            it.writeText(json, Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + PROVIDER_SUFFIX,
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
