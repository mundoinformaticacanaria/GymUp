package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveReadResult
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import com.mundoinformaticacanaria.gymup.domain.backup.BackupManifestCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupManifestV1
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {
    private val imageKey = "user:00000000-0000-0000-0000-000000000001"

    @Test
    fun `export delegates snapshot into validated archive`() = runBlocking {
        val snapshot = BackupSnapshot(
            data = "db".encodeToByteArray(),
            images = mapOf(imageKey to "image".encodeToByteArray()),
        )
        val source = FakeDataSource(snapshot)
        val archive = BackupManager(source, "0.1.0").export(Instant.parse("2026-08-21T03:00:00Z"))

        val read = BackupArchiveCodec.readAndValidate(archive)
        assertTrue(read is BackupArchiveReadResult.Valid)
        read as BackupArchiveReadResult.Valid
        assertArrayEquals(snapshot.data, read.snapshot.data)
        assertArrayEquals(snapshot.images.getValue(imageKey), read.snapshot.images.getValue(imageKey))
    }

    @Test
    fun `valid import replaces all only after validation`() = runBlocking {
        val source = FakeDataSource(BackupSnapshot("current".encodeToByteArray()))
        val restored = BackupSnapshot("restored".encodeToByteArray())
        val archive = BackupArchiveCodec.create(restored, "0.1.0")

        val result = BackupManager(source, "0.1.0").importReplaceAll(archive)

        assertEquals(BackupImportResult.Imported(0), result)
        assertArrayEquals(restored.data, source.replaced?.data)
    }

    @Test
    fun `invalid import never mutates current data`() = runBlocking {
        val source = FakeDataSource(BackupSnapshot("current".encodeToByteArray()))

        val result = BackupManager(source, "0.1.0").importReplaceAll("not-a-zip".encodeToByteArray())

        assertTrue(result is BackupImportResult.Rejected)
        assertFalse(source.replaceCalled)
    }

    @Test
    fun `valid import restores declared images along with data`() = runBlocking {
        val source = FakeDataSource(BackupSnapshot("current".encodeToByteArray()))
        val restored = BackupSnapshot(
            data = "restored".encodeToByteArray(),
            images = mapOf(imageKey to byteArrayOf(1, 2, 3)),
        )
        val archive = BackupArchiveCodec.create(restored, "0.1.0")

        val result = BackupManager(source, "0.1.0").importReplaceAll(archive)

        assertEquals(BackupImportResult.Imported(1), result)
        assertArrayEquals(restored.data, requireNotNull(source.replaced).data)
        assertArrayEquals(
            restored.images.getValue(imageKey),
            requireNotNull(source.replaced).images.getValue(imageKey),
        )
    }

    @Test
    fun `checksum mismatch never calls destructive replace all`() = runBlocking {
        val source = FakeDataSource(BackupSnapshot("current".encodeToByteArray()))
        val declared = "original".encodeToByteArray()
        val manifest = BackupManifestCodec.create(
            data = declared,
            images = emptyMap(),
            appVersion = "0.1.0",
            exportedAt = Instant.parse("2026-10-25T00:00:00Z"),
        )
        val archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf(
                    BackupManifestV1.MANIFEST_PATH to BackupManifestCodec.encode(manifest).encodeToByteArray(),
                    BackupManifestV1.DATA_PATH to "tampered".encodeToByteArray(),
                ).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

        val result = BackupManager(source, "0.1.0").importReplaceAll(archive)

        assertTrue(result is BackupImportResult.Rejected)
        assertFalse(source.replaceCalled)
        assertEquals(null, source.replaced)
    }

    private class FakeDataSource(
        private val exported: BackupSnapshot,
    ) : BackupDataSource {
        var replaceCalled = false
            private set
        var replaced: BackupSnapshot? = null
            private set

        override suspend fun exportSnapshot(): BackupSnapshot = exported

        override suspend fun replaceAll(snapshot: BackupSnapshot) {
            replaceCalled = true
            replaced = snapshot
        }
    }
}
