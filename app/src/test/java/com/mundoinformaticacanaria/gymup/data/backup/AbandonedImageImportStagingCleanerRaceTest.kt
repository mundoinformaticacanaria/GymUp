package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression for a candidate replaced after path preflight.
 * This isolated cleaner is NOT wired into production startup.
 */
class AbandonedImageImportStagingCleanerRaceTest {
    @get:Rule val temp = TemporaryFolder()

    private val activeImage = "11111111-1111-4111-8111-111111111111"
    private val stagedImage = "22222222-2222-4222-8222-222222222222"
    private val stageName = "exercise-images-import-33333333-3333-4333-8333-333333333333"

    private fun prepare(): File {
        val active = temp.newFolder("exercise-images")
        File(active, activeImage).writeBytes(byteArrayOf(1, 2, 3))
        return temp.newFolder(stageName).also {
            File(it, stagedImage).writeBytes(byteArrayOf(4, 5, 6))
        }
    }

    @Test
    fun `swapped staging symlink cannot delete outside bytes`() {
        val staging = prepare()
        val moved = File(temp.root, "original-staging")
        val outside = temp.newFolder("outside")
        val external = File(outside, stagedImage).apply { writeBytes(byteArrayOf(8, 9, 10)) }
        val cleaner = AbandonedImageImportStagingCleaner(
            temp.root,
            syncParent = {},
            beforeCandidateDelete = { candidate ->
                assertEquals(staging, candidate)
                assertTrue(candidate.renameTo(moved))
                Files.createSymbolicLink(candidate.toPath(), outside.toPath())
            },
        )

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner.cleanupAfterRecovery(setOf("user:$activeImage")),
        )
        assertTrue(Files.isSymbolicLink(staging.toPath()))
        assertArrayEquals(byteArrayOf(8, 9, 10), external.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(moved, stagedImage).readBytes())
    }

    @Test
    fun `byte identical replacement directory is preserved after preflight`() {
        val staging = prepare()
        val moved = File(temp.root, "original-staging")
        val cleaner = AbandonedImageImportStagingCleaner(
            temp.root,
            syncParent = {},
            beforeCandidateDelete = { candidate ->
                assertTrue(candidate.renameTo(moved))
                assertTrue(candidate.mkdir())
                File(candidate, stagedImage).writeBytes(byteArrayOf(4, 5, 6))
            },
        )

        assertEquals(
            AbandonedImageImportStagingCleaner.Result(0, 1),
            cleaner.cleanupAfterRecovery(setOf("user:$activeImage")),
        )
        assertArrayEquals(byteArrayOf(4, 5, 6), File(staging, stagedImage).readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(moved, stagedImage).readBytes())
    }
}
