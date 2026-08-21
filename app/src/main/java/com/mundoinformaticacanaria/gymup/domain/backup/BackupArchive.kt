package com.mundoinformaticacanaria.gymup.domain.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSnapshot(
    val data: ByteArray,
    val images: Map<String, ByteArray> = emptyMap(),
)

sealed interface BackupArchiveReadResult {
    data class Valid(
        val manifest: BackupManifestV1,
        val snapshot: BackupSnapshot,
    ) : BackupArchiveReadResult

    data class Invalid(val reason: String) : BackupArchiveReadResult
    data class ValidationFailed(val result: BackupValidationResult) : BackupArchiveReadResult
}

object BackupArchiveCodec {
    private const val MAX_ENTRY_BYTES = 25L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
    private const val MAX_ENTRIES = 1_000

    fun create(
        snapshot: BackupSnapshot,
        appVersion: String,
        exportedAt: Instant = Instant.now(),
    ): ByteArray {
        val manifest = BackupManifestCodec.create(
            data = snapshot.data,
            images = snapshot.images,
            appVersion = appVersion,
            exportedAt = exportedAt,
        )
        val manifestBytes = BackupManifestCodec.encode(manifest).encodeToByteArray()

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, BackupManifestV1.MANIFEST_PATH, manifestBytes)
                writeEntry(zip, BackupManifestV1.DATA_PATH, snapshot.data)
                manifest.images.forEach { image ->
                    writeEntry(zip, image.path, snapshot.images.getValue(image.storageKey))
                }
            }
            output.toByteArray()
        }
    }

    fun readAndValidate(archive: ByteArray): BackupArchiveReadResult {
        if (archive.size.toLong() > MAX_ARCHIVE_BYTES) {
            return BackupArchiveReadResult.Invalid("El backup supera el tamaño máximo permitido")
        }

        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entryCount = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    return BackupArchiveReadResult.Invalid("El backup contiene demasiados archivos")
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val path = entry.name
                if (!BackupManifestCodec.isSafeRelativePath(path)) {
                    return BackupArchiveReadResult.Invalid("Ruta ZIP no válida: $path")
                }
                if (entries.containsKey(path)) {
                    return BackupArchiveReadResult.Invalid("Archivo ZIP duplicado: $path")
                }
                val bytes = readEntry(zip) ?: return BackupArchiveReadResult.Invalid(
                    "El archivo $path supera el tamaño máximo permitido",
                )
                entries[path] = bytes
                zip.closeEntry()
            }
        }

        val manifestRaw = entries.remove(BackupManifestV1.MANIFEST_PATH)
            ?: return BackupArchiveReadResult.Invalid("Falta ${BackupManifestV1.MANIFEST_PATH}")
        val manifest = BackupManifestCodec.decode(manifestRaw.decodeToString()).getOrElse {
            return BackupArchiveReadResult.Invalid("Manifest no válido: ${it.message ?: "JSON inválido"}")
        }

        val declaredPaths = BackupManifestCodec.declaredPaths(manifest)
        val unexpected = entries.keys.firstOrNull { it !in declaredPaths }
        if (unexpected != null) {
            return BackupArchiveReadResult.Invalid("Archivo no declarado en manifest: $unexpected")
        }

        val validation = BackupManifestCodec.validate(manifest, entries)
        if (validation != BackupValidationResult.Valid) {
            return BackupArchiveReadResult.ValidationFailed(validation)
        }

        val images = manifest.images.associate { image ->
            image.storageKey to entries.getValue(image.path)
        }
        return BackupArchiveReadResult.Valid(
            manifest = manifest,
            snapshot = BackupSnapshot(
                data = entries.getValue(manifest.data.path),
                images = images,
            ),
        )
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        require(BackupManifestCodec.isSafeRelativePath(path)) { "Ruta de backup no válida: $path" }
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readEntry(zip: ZipInputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
