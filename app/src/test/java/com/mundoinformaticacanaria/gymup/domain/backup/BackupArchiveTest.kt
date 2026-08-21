package com.mundoinformaticacanaria.gymup.domain.backup

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveTest {
    @Test
    fun `create and read round trip preserves declared files`() {
        val files = mapOf(
            BackupManifestV1.DATA_PATH to "{\"sessions\":[]}".encodeToByteArray(),
            "images/custom-1.jpg" to byteArrayOf(1, 2, 3, 4),
        )

        val archive = BackupArchiveCodec.create(files, Instant.parse("2026-08-21T00:00:00Z"))
        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Valid)
        result as BackupArchiveReadResult.Valid
        assertEquals(1, result.manifest.schemaVersion)
        assertEquals(files.keys, result.files.keys)
        files.forEach { (path, expected) -> assertArrayEquals(expected, result.files.getValue(path)) }
    }

    @Test
    fun `read rejects undeclared file`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            mapOf(BackupManifestV1.DATA_PATH to data),
            Instant.parse("2026-08-21T00:00:00Z"),
        )
        val archive = zipOf(
            BackupManifestV1.MANIFEST_PATH to BackupManifestCodec.encode(manifest).encodeToByteArray(),
            BackupManifestV1.DATA_PATH to data,
            "extra.txt" to "unexpected".encodeToByteArray(),
        )

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("no declarado"))
    }

    @Test
    fun `read rejects zip slip path before import`() {
        val archive = zipOf("../data.json" to "{}".encodeToByteArray())

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("Ruta ZIP no válida"))
    }

    @Test
    fun `read reports checksum mismatch for tampered declared file`() {
        val original = "original".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            mapOf(BackupManifestV1.DATA_PATH to original),
            Instant.parse("2026-08-21T00:00:00Z"),
        )
        val archive = zipOf(
            BackupManifestV1.MANIFEST_PATH to BackupManifestCodec.encode(manifest).encodeToByteArray(),
            BackupManifestV1.DATA_PATH to "tampered".encodeToByteArray(),
        )

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertEquals(
            BackupArchiveReadResult.ValidationFailed(
                BackupValidationResult.ChecksumMismatch(BackupManifestV1.DATA_PATH),
            ),
            result,
        )
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
