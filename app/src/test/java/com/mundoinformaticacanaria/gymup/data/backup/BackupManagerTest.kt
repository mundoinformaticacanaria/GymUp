package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupManifestV1
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {
    @Test
    fun `export delegates snapshot into validated archive`() {
        val source = FakeDataSource(
            mapOf(
                BackupManifestV1.DATA_PATH to "db".encodeToByteArray(),
                "images/user-1.jpg" to "image".encodeToByteArray(),
            ),
        )
        val archive = BackupManager(source).export(Instant.parse("2026-08-21T03:00:00Z"))

        val read = BackupArchiveCodec.readAndValidate(archive)
        assertTrue(read is com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveReadResult.Valid)
    }

    @Test
    fun `valid import replaces all only after validation`() {
        val source = FakeDataSource(emptyMap())
        val files = mapOf(BackupManifestV1.DATA_PATH to "restored".encodeToByteArray())
        val archive = BackupArchiveCodec.create(files)

        val result = BackupManager(source).importReplaceAll(archive)

        assertEquals(BackupImportResult.Imported(1), result)
        assertEquals(files.keys, source.replaced?.keys)
        assertTrue(files.getValue(BackupManifestV1.DATA_PATH).contentEquals(source.replaced!!.getValue(BackupManifestV1.DATA_PATH)))
    }

    @Test
    fun `invalid import never mutates current data`() {
        val source = FakeDataSource(emptyMap())

        val result = BackupManager(source).importReplaceAll("not-a-zip".encodeToByteArray())

        assertTrue(result is BackupImportResult.Rejected)
        assertFalse(source.replaceCalled)
    }

    private class FakeDataSource(
        private val exported: Map<String, ByteArray>,
    ) : BackupDataSource {
        var replaceCalled = false
            private set
        var replaced: Map<String, ByteArray>? = null
            private set

        override fun exportFiles(): Map<String, ByteArray> = exported

        override fun replaceAll(files: Map<String, ByteArray>) {
            replaceCalled = true
            replaced = files
        }
    }
}
