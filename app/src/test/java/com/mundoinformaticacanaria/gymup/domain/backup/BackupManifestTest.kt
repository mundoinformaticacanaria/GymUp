package com.mundoinformaticacanaria.gymup.domain.backup

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupManifestTest {
    private val exportedAt = Instant.parse("2026-08-21T00:00:00Z")

    @Test
    fun `create produces stable sorted checksummed entries`() {
        val files = mapOf(
            "images/custom/b.jpg" to byteArrayOf(2, 3),
            BackupManifestV1.DATA_PATH to "{}".encodeToByteArray(),
            "images/custom/a.jpg" to byteArrayOf(1),
        )

        val manifest = BackupManifestCodec.create(files, exportedAt)

        assertEquals(1, manifest.schemaVersion)
        assertEquals(exportedAt.toString(), manifest.exportedAt)
        assertEquals(
            listOf(BackupManifestV1.DATA_PATH, "images/custom/a.jpg", "images/custom/b.jpg"),
            manifest.files.map { it.path },
        )
        assertTrue(manifest.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun `validate accepts intact backup`() {
        val files = mapOf(
            BackupManifestV1.DATA_PATH to "{\"schema_version\":1}".encodeToByteArray(),
            "images/custom/exercise.jpg" to byteArrayOf(1, 2, 3, 4),
        )
        val manifest = BackupManifestCodec.create(files, exportedAt)

        assertEquals(BackupValidationResult.Valid, BackupManifestCodec.validate(manifest, files))
    }

    @Test
    fun `validate rejects unsupported manifest version before import`() {
        val files = mapOf(BackupManifestV1.DATA_PATH to "{}".encodeToByteArray())
        val manifest = BackupManifestCodec.create(files, exportedAt).copy(schemaVersion = 2)

        assertEquals(
            BackupValidationResult.UnsupportedVersion(2),
            BackupManifestCodec.validate(manifest, files),
        )
    }

    @Test
    fun `validate rejects missing file`() {
        val files = mapOf(BackupManifestV1.DATA_PATH to "{}".encodeToByteArray())
        val manifest = BackupManifestCodec.create(files, exportedAt)

        assertEquals(
            BackupValidationResult.MissingFile(BackupManifestV1.DATA_PATH),
            BackupManifestCodec.validate(manifest, emptyMap()),
        )
    }

    @Test
    fun `validate rejects tampered contents`() {
        val original = mapOf(BackupManifestV1.DATA_PATH to "original".encodeToByteArray())
        val manifest = BackupManifestCodec.create(original, exportedAt)
        val tampered = mapOf(BackupManifestV1.DATA_PATH to "tampered".encodeToByteArray())

        assertIs<BackupValidationResult.SizeMismatch>(BackupManifestCodec.validate(manifest, tampered))
    }

    @Test
    fun `validate rejects checksum mismatch with same byte count`() {
        val original = mapOf(BackupManifestV1.DATA_PATH to "abcd".encodeToByteArray())
        val manifest = BackupManifestCodec.create(original, exportedAt)
        val tampered = mapOf(BackupManifestV1.DATA_PATH to "wxyz".encodeToByteArray())

        assertEquals(
            BackupValidationResult.ChecksumMismatch(BackupManifestV1.DATA_PATH),
            BackupManifestCodec.validate(manifest, tampered),
        )
    }

    @Test
    fun `unsafe zip paths are rejected`() {
        val result = runCatching {
            BackupManifestCodec.create(
                mapOf(
                    BackupManifestV1.DATA_PATH to "{}".encodeToByteArray(),
                    "../outside.jpg" to byteArrayOf(1),
                ),
                exportedAt,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `manifest round trip preserves contract`() {
        val files = mapOf(BackupManifestV1.DATA_PATH to "{}".encodeToByteArray())
        val original = BackupManifestCodec.create(files, exportedAt)

        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original)).getOrThrow()

        assertEquals(original, decoded)
    }
}
