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
    private val imageKey = "user:00000000-0000-0000-0000-000000000001"

    @Test
    fun `create and read round trip preserves data and images`() {
        val snapshot = BackupSnapshot(
            data = "{\"sessions\":[]}".encodeToByteArray(),
            images = mapOf(imageKey to byteArrayOf(1, 2, 3, 4)),
        )

        val archive = BackupArchiveCodec.create(
            snapshot = snapshot,
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Valid)
        result as BackupArchiveReadResult.Valid
        assertEquals(1, result.manifest.schemaVersion)
        assertEquals("0.1.0", result.manifest.appVersion)
        assertArrayEquals(snapshot.data, result.snapshot.data)
        assertArrayEquals(snapshot.images.getValue(imageKey), result.snapshot.images.getValue(imageKey))
    }

    @Test
    fun `read rejects undeclared file`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            data = data,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
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
            data = original,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
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
