package com.mundoinformaticacanaria.gymup.domain.backup

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
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
    fun `read rejects malformed UTF8 data even when its manifest checksum matches`() {
        val data = """{"name":"X"}""".encodeToByteArray()
        val invalidByte = data.indexOf('X'.code.toByte())
        assertTrue(invalidByte >= 0)
        data[invalidByte] = 0xFF.toByte()
        val manifest = BackupManifestCodec.create(
            data = data,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
        val archive = zipOf(
            BackupManifestV1.MANIFEST_PATH to BackupManifestCodec.encode(manifest).encodeToByteArray(),
            BackupManifestV1.DATA_PATH to data,
        )

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("UTF-8"))
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
    fun `read rejects unsafe ZIP directory even when data and manifest are valid`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            data = data,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
        val archive = zipOf(
            "../" to byteArrayOf(),
            BackupManifestV1.MANIFEST_PATH to BackupManifestCodec.encode(manifest).encodeToByteArray(),
            BackupManifestV1.DATA_PATH to data,
        )

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("Ruta ZIP no válida"))
    }

    @Test
    fun `read allows safe ZIP directory entries without changing valid backups`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            data = data,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
        val archive = zipOf(
            "images/" to byteArrayOf(),
            BackupManifestV1.MANIFEST_PATH to BackupManifestCodec.encode(manifest).encodeToByteArray(),
            BackupManifestV1.DATA_PATH to data,
        )

        assertTrue(BackupArchiveCodec.readAndValidate(archive) is BackupArchiveReadResult.Valid)
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

    @Test
    fun `read rejects aggregate ZIP expansion even when each entry fits its own limit`() {
        val archive = zipOf(
            "images/first" to ByteArray(80) { 1 },
            "images/second" to ByteArray(80) { 2 },
        )
        val result = BackupArchiveCodec.readAndValidateWithExpandedLimit(
            archive,
            maxExpandedBytes = 128,
        )
        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue(
            (result as BackupArchiveReadResult.Invalid).reason.contains("descomprimido total"),
        )
    }

    @Test
    fun `read accepts exact aggregate boundary and rejects one byte less`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            data = data,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
        val manifestBytes = BackupManifestCodec.encode(manifest).encodeToByteArray()
        val archive = zipOf(
            BackupManifestV1.MANIFEST_PATH to manifestBytes,
            BackupManifestV1.DATA_PATH to data,
        )
        val expandedBytes = manifestBytes.size.toLong() + data.size.toLong()
        assertTrue(
            BackupArchiveCodec.readAndValidateWithExpandedLimit(archive, expandedBytes)
                is BackupArchiveReadResult.Valid,
        )
        val rejected = BackupArchiveCodec.readAndValidateWithExpandedLimit(
            archive,
            expandedBytes - 1,
        )
        assertTrue(rejected is BackupArchiveReadResult.Invalid)
        assertTrue(
            (rejected as BackupArchiveReadResult.Invalid).reason.contains("descomprimido total"),
        )
    }

    @Test
    fun `read rejects a corrupted ZIP CRC without throwing`() {
        val payload = "unique stored payload for CRC check".encodeToByteArray()
        val archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry(BackupManifestV1.DATA_PATH).apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = payload.size.toLong()
                    crc = CRC32().apply { update(payload) }.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val payloadOffset = archive.indices.firstOrNull { offset ->
            offset + payload.size <= archive.size &&
                payload.indices.all { archive[offset + it] == payload[it] }
        } ?: error("No se encontró el payload almacenado en el ZIP de prueba")
        archive[payloadOffset] = (archive[payloadOffset].toInt() xor 1).toByte()

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("ZIP corrupto"))
    }

    @Test
    fun `read rejects malformed UTF8 ZIP entry name without throwing`() {
        val archive = zipOf(BackupManifestV1.DATA_PATH to "{}".encodeToByteArray())
        // Local ZIP header: 30 fixed bytes followed by the entry filename.
        // 0xFF alone is not valid UTF-8 and ZipInputStream may throw
        // IllegalArgumentException while decoding the name.
        assertEquals(0x50, archive[0].toInt() and 0xFF)
        assertEquals(0x4B, archive[1].toInt() and 0xFF)
        assertEquals(0x03, archive[2].toInt() and 0xFF)
        assertEquals(0x04, archive[3].toInt() and 0xFF)
        archive[30] = 0xFF.toByte()

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("ZIP corrupto"))
    }

    @Test
    fun `round trip accepts 998 images at the existing ZIP entry limit`() {
        // Two fixed entries (manifest and data) plus 998 images = 1,000.
        val archive = BackupArchiveCodec.create(
            snapshot = BackupSnapshot(
                data = "{}".encodeToByteArray(),
                images = numberedImageEntries(998),
            ),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )

        val result = BackupArchiveCodec.readAndValidate(archive)
        assertTrue(result is BackupArchiveReadResult.Valid)
        assertEquals(998, (result as BackupArchiveReadResult.Valid).snapshot.images.size)
    }

    @Test
    fun `exporter can create 999 images that the current reader rejects by entry count`() {
        // This is a regression fixture for #58, not approval to change ZIP v1 limits.
        // Two fixed entries plus 999 images exceed the existing 1,000-entry cap.
        val archive = BackupArchiveCodec.create(
            snapshot = BackupSnapshot(
                data = "{}".encodeToByteArray(),
                images = numberedImageEntries(999),
            ),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )

        val result = BackupArchiveCodec.readAndValidate(archive)
        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("demasiados archivos"))
    }

    @Test
    fun `exporter can create a data entry exceeding the existing per entry reader limit`() {
        // The exporter currently has no symmetric 25 MiB check (#58).
        // Highly compressible bytes keep the ZIP fixture small; this is not a
        // proposed change to the format or an approval of new export limits.
        val oversizedData = ByteArray(25 * 1024 * 1024 + 1) { 1 }
        val archive = BackupArchiveCodec.create(
            snapshot = BackupSnapshot(data = oversizedData),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )

        assertTrue(archive.size < 100 * 1024 * 1024)
        val result = BackupArchiveCodec.readAndValidate(archive)
        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue(
            (result as BackupArchiveReadResult.Invalid).reason.contains("supera el tamaño máximo"),
        )
    }

    @Test
    fun `read rejects malformed UTF8 manifest instead of silently replacing metadata`() {
        val data = "{}".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            data = data,
            images = emptyMap(),
            appVersion = "build-X",
            exportedAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
        val raw = BackupManifestCodec.encode(manifest).encodeToByteArray()
        val marker = "build-X".encodeToByteArray()
        val offset = raw.indices.firstOrNull { start ->
            start + marker.size <= raw.size &&
                marker.indices.all { raw[start + it] == marker[it] }
        } ?: error("No se encontró app_version en el manifest de prueba")
        // 0xFF is not valid UTF-8. A permissive decode would replace it with U+FFFD,
        // leaving otherwise valid JSON and allowing the altered version metadata.
        raw[offset + marker.lastIndex] = 0xFF.toByte()
        val archive = zipOf(
            BackupManifestV1.MANIFEST_PATH to raw,
            BackupManifestV1.DATA_PATH to data,
        )

        val result = BackupArchiveCodec.readAndValidate(archive)

        assertTrue(result is BackupArchiveReadResult.Invalid)
        assertTrue((result as BackupArchiveReadResult.Invalid).reason.contains("UTF-8"))
    }

    private fun numberedImageEntries(count: Int): Map<String, ByteArray> =
        (1..count).associate { index ->
            "user:00000000-0000-0000-0000-${index.toString().padStart(12, '0')}" to byteArrayOf(1)
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
