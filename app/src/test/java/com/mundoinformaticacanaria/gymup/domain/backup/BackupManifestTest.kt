package com.mundoinformaticacanaria.gymup.domain.backup

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {
    private val exportedAt = Instant.parse("2026-08-21T00:00:00Z")
    private val imageA = "user:00000000-0000-0000-0000-000000000001"
    private val imageB = "user:00000000-0000-0000-0000-000000000002"

    @Test
    fun `create produces schema aligned checksummed manifest`() {
        val data = "{}".encodeToByteArray()
        val images = linkedMapOf(
            imageB to byteArrayOf(2, 3),
            imageA to byteArrayOf(1),
        )

        val manifest = BackupManifestCodec.create(data, images, "0.1.0", exportedAt)

        assertEquals(BackupManifestV1.FORMAT, manifest.format)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(exportedAt.toString(), manifest.exportedAt)
        assertEquals("0.1.0", manifest.appVersion)
        assertEquals(BackupManifestV1.DATA_PATH, manifest.data.path)
        assertEquals(listOf(imageA, imageB), manifest.images.map { it.storageKey })
        assertTrue(manifest.data.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(manifest.images.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun `validate accepts intact backup`() {
        val data = "{\"schema_version\":1}".encodeToByteArray()
        val images = mapOf(imageA to byteArrayOf(1, 2, 3, 4))
        val manifest = BackupManifestCodec.create(data, images, "0.1.0", exportedAt)
        val files = mapOf(
            BackupManifestV1.DATA_PATH to data,
            BackupManifestCodec.imagePath(imageA) to images.getValue(imageA),
        )

        assertEquals(BackupValidationResult.Valid, BackupManifestCodec.validate(manifest, files))
    }

    @Test
    fun `validate rejects unsupported manifest version before import`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(data, emptyMap(), "0.1.0", exportedAt).copy(schemaVersion = 2)

        assertEquals(
            BackupValidationResult.UnsupportedVersion(2),
            BackupManifestCodec.validate(manifest, mapOf(BackupManifestV1.DATA_PATH to data)),
        )
    }

    @Test
    fun `validate rejects unsupported format`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(data, emptyMap(), "0.1.0", exportedAt).copy(format = "other")

        assertEquals(
            BackupValidationResult.UnsupportedFormat("other"),
            BackupManifestCodec.validate(manifest, mapOf(BackupManifestV1.DATA_PATH to data)),
        )
    }

    @Test
    fun `validate rejects missing data file`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(data, emptyMap(), "0.1.0", exportedAt)

        assertEquals(
            BackupValidationResult.MissingFile(BackupManifestV1.DATA_PATH),
            BackupManifestCodec.validate(manifest, emptyMap()),
        )
    }

    @Test
    fun `validate rejects tampered contents`() {
        val data = "original".encodeToByteArray()
        val manifest = BackupManifestCodec.create(data, emptyMap(), "0.1.0", exportedAt)
        val tampered = mapOf(BackupManifestV1.DATA_PATH to "tampered!".encodeToByteArray())

        assertTrue(BackupManifestCodec.validate(manifest, tampered) is BackupValidationResult.SizeMismatch)
    }

    @Test
    fun `validate rejects checksum mismatch with same byte count`() {
        val data = "abcd".encodeToByteArray()
        val manifest = BackupManifestCodec.create(data, emptyMap(), "0.1.0", exportedAt)
        val tampered = mapOf(BackupManifestV1.DATA_PATH to "wxyz".encodeToByteArray())

        assertEquals(
            BackupValidationResult.ChecksumMismatch(BackupManifestV1.DATA_PATH),
            BackupManifestCodec.validate(manifest, tampered),
        )
    }

    @Test
    fun `invalid user storage key is rejected`() {
        val result = runCatching {
            BackupManifestCodec.create(
                data = "{}".encodeToByteArray(),
                images = mapOf("../outside.jpg" to byteArrayOf(1)),
                appVersion = "0.1.0",
                exportedAt = exportedAt,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `manifest round trip preserves contract`() {
        val original = BackupManifestCodec.create("{}".encodeToByteArray(), emptyMap(), "0.1.0", exportedAt)

        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original)).getOrThrow()

        assertEquals(original, decoded)
    }
}
