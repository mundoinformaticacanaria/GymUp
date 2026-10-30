package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** No production startup wiring until the Android 15/16 durability gates pass. */
class AbandonedImageImportStagingCleanerTest {
    @get:Rule val temp = TemporaryFolder()

    private val imageName = "11111111-1111-4111-8111-111111111111"
    private val otherName = "22222222-2222-4222-8222-222222222222"
    private val stageName = "exercise-images-import-33333333-3333-4333-8333-333333333333"
    private val previousName = "exercise-images-previous-44444444-4444-4444-8444-444444444444"

    private fun cleaner() = AbandonedImageImportStagingCleaner(temp.root, syncParent = {})

    private fun active(): File = temp.newFolder("exercise-images").also {
        File(it, imageName).writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun stage(): File = temp.newFolder(stageName).also {
        File(it, otherName).writeBytes(byteArrayOf(4, 5, 6))
    }

    @Test
    fun `cleans only safe abandoned import staging and keeps previous copy`() {
        active()
        val staging = stage()
        val previous = temp.newFolder(previousName)
        val old = File(previous, imageName).apply { writeBytes(byteArrayOf(9, 8)) }

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(1, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertFalse(staging.exists())
        assertArrayEquals(byteArrayOf(9, 8), old.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(temp.root, "exercise-images/$imageName").readBytes())
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
    }

    @Test
    fun `missing active root preserves staged copy after interrupted swap`() {
        val staging = stage()
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(emptySet()),
        )
        assertTrue(staging.exists())
    }

    @Test
    fun `missing referenced image preserves staging`() {
        active()
        val staging = stage()
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$otherName")),
        )
        assertTrue(staging.exists())
    }

    @Test
    fun `unsafe active root preserves staging`() {
        val outside = temp.newFolder("outside")
        File(outside, imageName).writeBytes(byteArrayOf(7))
        Files.createSymbolicLink(File(temp.root, "exercise-images").toPath(), outside.toPath())
        val staging = stage()

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(emptySet()),
        )
        assertTrue(staging.exists())
    }

    @Test
    fun `staging symlink is preserved without following target`() {
        active()
        val outside = temp.newFolder("outside")
        val payload = File(outside, otherName).apply { writeBytes(byteArrayOf(7, 8)) }
        val staging = File(temp.root, stageName)
        Files.createSymbolicLink(staging.toPath(), outside.toPath())

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertTrue(Files.isSymbolicLink(staging.toPath()))
        assertArrayEquals(byteArrayOf(7, 8), payload.readBytes())
    }

    @Test
    fun `nested staging and unexpected names are not removed`() {
        active()
        val staging = temp.newFolder(stageName)
        val nested = File(staging, "nested").apply { mkdir() }
        val payload = File(nested, "keep").apply { writeBytes(byteArrayOf(3)) }
        val unrelated = temp.newFolder("exercise-images-import-not-a-uuid")

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertTrue(payload.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `unexpected file in active root prevents irreversible staging cleanup`() {
        val root = active()
        val staging = stage()
        File(root, ".unrecognized.tmp").writeBytes(byteArrayOf(42))

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertTrue(staging.exists())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(staging, otherName).readBytes())
    }

    @Test
    fun `malformed persisted storage key preserves staging even with valid active root`() {
        active()
        val staging = stage()

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:../$imageName")),
        )
        assertTrue(staging.exists())
    }

    @Test
    fun `directory sync failure is reported and subsequent cleanup is idempotent`() {
        active()
        val staging = stage()
        val failure = runCatching {
            AbandonedImageImportStagingCleaner(temp.root, syncParent = {
                throw java.io.IOException("simulated parent fsync failure")
            }).cleanupAfterRecovery(setOf("user:$imageName"))
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        // This is not a durability guarantee: after a failed fsync, power-loss
        // recovery still requires validation on the actual Android filesystem.
        assertFalse(staging.exists())
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            File(temp.root, "exercise-images/$imageName").readBytes(),
        )
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
    }
}
