package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-only compatibility check for SecureDirectoryStream and directory fsync.
 * Run exclusively on a disposable Android 15/16 AVD; CI compiles but does not execute it.
 */
class AbandonedImageImportStagingCleanerDeviceTest {
    private val imageName = "11111111-1111-4111-8111-111111111111"
    private val stageImageName = "22222222-2222-4222-8222-222222222222"
    private val stageName = "exercise-images-import-33333333-3333-4333-8333-333333333333"

    @Test
    fun secureStagingCleanupWorksOnDevicePrivateFilesystem() {
        withIsolatedDirectory { root ->
            val active = File(root, "exercise-images").apply { assertTrue(mkdir()) }
            File(active, imageName).writeBytes(byteArrayOf(1, 2, 3))
            val staging = File(root, stageName).apply { assertTrue(mkdir()) }
            File(staging, stageImageName).writeBytes(byteArrayOf(4, 5, 6))

            assertEquals(
                AbandonedImageImportStagingCleaner.Result(1, 0),
                AbandonedImageImportStagingCleaner(root)
                    .cleanupAfterRecovery(setOf("user:$imageName")),
            )
            assertFalse(staging.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(active, imageName).readBytes())
        }
    }

    @Test
    fun swappedSymlinkCannotRedirectDeviceDeletion() {
        withIsolatedDirectory { root ->
            val active = File(root, "exercise-images").apply { assertTrue(mkdir()) }
            File(active, imageName).writeBytes(byteArrayOf(1, 2, 3))
            val staging = File(root, stageName).apply { assertTrue(mkdir()) }
            File(staging, stageImageName).writeBytes(byteArrayOf(4, 5, 6))
            val original = File(root, "original-staging")
            val external = File(root, "external").apply { assertTrue(mkdir()) }
            val sentinel = File(external, stageImageName).apply { writeBytes(byteArrayOf(9, 8)) }

            assertEquals(
                AbandonedImageImportStagingCleaner.Result(0, 1),
                AbandonedImageImportStagingCleaner(
                    root,
                    beforeCandidateDelete = { candidate ->
                        assertTrue(candidate.renameTo(original))
                        Files.createSymbolicLink(candidate.toPath(), external.toPath())
                    },
                ).cleanupAfterRecovery(setOf("user:$imageName")),
            )
            assertArrayEquals(byteArrayOf(9, 8), sentinel.readBytes())
            assertArrayEquals(byteArrayOf(4, 5, 6), File(original, stageImageName).readBytes())
        }
    }

    private fun withIsolatedDirectory(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "gymup-cleaner-test-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            block(root)
        } finally {
            // Only the UUID-scoped test directory, never the app's actual image store.
            root.deleteRecursively()
        }
    }
}
