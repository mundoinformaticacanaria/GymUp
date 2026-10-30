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
    fun `mixed staging candidates remove only safe directory and preserve linked payload`() {
        active()
        val safe = stage()
        val unsafe = temp.newFolder("exercise-images-import-55555555-5555-4555-8555-555555555555")
        val outside = File(temp.root, "outside-image").apply { writeBytes(byteArrayOf(9, 7, 5)) }
        val linked = File(unsafe, imageName)
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val previous = temp.newFolder(previousName)
        val recoverable = File(previous, otherName).apply { writeBytes(byteArrayOf(6, 4)) }

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(1, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertFalse(safe.exists())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
        assertArrayEquals(byteArrayOf(9, 7, 5), outside.readBytes())
        assertArrayEquals(byteArrayOf(6, 4), recoverable.readBytes())
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
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

    @Test
    fun `empty or oversized staging image is preserved while a safe sibling is removed`() {
        active()
        val safe = stage()
        val empty = temp.newFolder("exercise-images-import-66666666-6666-4666-8666-666666666666")
        val emptyImage = File(empty, imageName).apply { createNewFile() }
        val oversized = temp.newFolder("exercise-images-import-77777777-7777-4777-8777-777777777777")
        val oversizedImage = File(oversized, otherName)
        java.io.RandomAccessFile(oversizedImage, "rw").use {
            it.setLength(10L * 1024 * 1024 + 1)
        }

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(1, 2),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertFalse(safe.exists())
        assertTrue(emptyImage.exists())
        assertEquals(0L, emptyImage.length())
        assertTrue(oversizedImage.exists())
        assertEquals(10L * 1024 * 1024 + 1, oversizedImage.length())
    }

    @Test
    fun `invalid active image sizes preserve all staging until the root is repaired`() {
        val root = active()
        val staging = stage()
        val activeImage = File(root, imageName)
        activeImage.writeBytes(byteArrayOf())

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertTrue(staging.exists())

        java.io.RandomAccessFile(activeImage, "rw").use {
            it.setLength(10L * 1024 * 1024 + 1)
        }
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertTrue(staging.exists())

        activeImage.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(
            AbandonedImageImportStagingCleaner.Result(1, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertFalse(staging.exists())
    }


    @Test
    fun `uppercase backup accepted staging image can be cleaned when active root is safe`() {
        active()
        val safe = stage()
        val noncanonical = temp.newFolder("exercise-images-import-88888888-8888-4888-8888-888888888888")
        // ZIP v1 accepts uppercase hexadecimal keys; the cleaner must use the
        // same safe filename character set without requiring canonical UUIDs.
        val upperCaseName = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        val image = File(noncanonical, upperCaseName).apply { writeBytes(byteArrayOf(7, 4)) }

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(2, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertFalse(safe.exists())
        assertFalse(noncanonical.exists())
        assertFalse(image.exists())
    }

    @Test
    fun `uppercase backup accepted active USER reference allows safe staging cleanup`() {
        val root = active()
        val staging = stage()
        val upperCaseName = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        File(root, upperCaseName).writeBytes(byteArrayOf(5))

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(1, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$upperCaseName")),
        )
        assertFalse(staging.exists())
        assertArrayEquals(byteArrayOf(5), File(root, upperCaseName).readBytes())
    }

    @Test
    fun `legacy noncanonical 36 character hex hyphen filename matches ZIP v1 safely`() {
        val root = active()
        val legacyName = "A".repeat(35) + "-"
        val legacyImage = File(root, legacyName).apply { writeBytes(byteArrayOf(5, 4, 3)) }
        val staging = temp.newFolder("exercise-images-import-99999999-9999-4999-8999-999999999999")
        File(staging, legacyName).writeBytes(byteArrayOf(8, 7))

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(1, 0),
            cleaner().cleanupAfterRecovery(setOf("user:$legacyName")),
        )
        assertFalse(staging.exists())
        assertArrayEquals(byteArrayOf(5, 4, 3), legacyImage.readBytes())
    }

    @Test
    fun `symlinked active USER image preserves staging and external bytes`() {
        val root = temp.newFolder("exercise-images")
        val external = File(temp.root, "external-image").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }
        val linked = File(root, imageName)
        Files.createSymbolicLink(linked.toPath(), external.toPath())
        val staging = stage()

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner().cleanupAfterRecovery(setOf("user:$imageName")),
        )
        assertTrue(staging.exists())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
        assertArrayEquals(byteArrayOf(9, 8, 7), external.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(staging, otherName).readBytes())
    }

}
