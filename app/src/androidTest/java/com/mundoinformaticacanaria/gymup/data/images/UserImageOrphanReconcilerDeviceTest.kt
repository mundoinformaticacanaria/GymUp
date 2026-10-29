package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-device checks for #56: real app-private directory fsync and NOFOLLOW behavior.
 *
 * The image root is deliberately isolated from GymUp's actual filesDir. These tests must
 * never reconcile or delete images belonging to an installed user's application.
 * JVM/Robolectric CI does not run these tests. They do not simulate process/power loss.
 */
class UserImageOrphanReconcilerDeviceTest {
    @Test
    fun canonicalOrphanIsDeletedWithRealDirectorySync() = withIsolatedStorage { context, root ->
        val images = File(root, "exercise-images").apply { check(mkdirs()) }
        val orphan = File(images, UUID.randomUUID().toString()).apply { writeText("orphan") }
        val unknown = File(images, "legacy-photo.jpg").apply { writeText("preserve") }

        withInMemoryDatabase(context) { database ->
            // Default syncDirectory uses FileChannel.force(true) on the real device.
            UserImageOrphanReconciler(context, database.exerciseDao()).reconcile()
        }

        assertFalse(orphan.exists())
        assertEquals("preserve", unknown.readText())
    }

    @Test
    fun sharedReferencedImageSurvivesReconciliationWithRealRoom() = withIsolatedStorage { context, root ->
        val images = File(root, "exercise-images").apply { check(mkdirs()) }
        val sharedName = UUID.randomUUID().toString()
        val shared = File(images, sharedName).apply { writeText("referenced bytes") }
        val orphan = File(images, UUID.randomUUID().toString()).apply { writeText("orphan bytes") }

        withInMemoryDatabase(context) { database ->
            // Only seed the disposable in-memory database, never the installed app's Room file.
            DatabaseSeeder(context, database).seedIfNeeded()
            val exerciseId = database.exerciseDao().getAll().first().id
            // storage_key has no global UNIQUE constraint; two rows may share one USER file.
            for (position in 1001..1002) {
                database.exerciseDao().insertImage(
                    ExerciseImageEntity(
                        id = UUID.randomUUID().toString(),
                        exerciseId = exerciseId,
                        position = position,
                        sourceType = "USER",
                        storageKey = "user:$sharedName",
                        originalSourceUrl = null,
                        author = null,
                        license = null,
                    ),
                )
            }
            UserImageOrphanReconciler(context, database.exerciseDao()).reconcile()
            assertEquals(
                2,
                database.exerciseDao().countUserImageStorageKeyReferences("user:$sharedName"),
            )
        }

        assertEquals("referenced bytes", shared.readText())
        assertFalse(orphan.exists())
    }

    @Test
    fun canonicalSymlinkFailsClosedWithoutDeletingExternalTarget() = withIsolatedStorage { context, root ->
        val images = File(root, "exercise-images").apply { check(mkdirs()) }
        val external = File(root, "external").apply { writeText("valuable bytes") }
        val link = File(images, UUID.randomUUID().toString())
        Files.createSymbolicLink(link.toPath(), external.toPath())

        withInMemoryDatabase(context) { database ->
            assertThrows(IOException::class.java) {
                runBlocking {
                    UserImageOrphanReconciler(context, database.exerciseDao()).reconcile()
                }
            }
        }

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("valuable bytes", external.readText())
    }

    @Test
    fun symlinkedImageRootFailsClosedOnDevice() = withIsolatedStorage { context, root ->
        val externalDirectory = File(root, "external-images").apply { check(mkdirs()) }
        val externalImage = File(externalDirectory, UUID.randomUUID().toString())
            .apply { writeText("keep external bytes") }
        val imageRoot = File(root, "exercise-images")
        Files.createSymbolicLink(imageRoot.toPath(), externalDirectory.toPath())

        withInMemoryDatabase(context) { database ->
            assertThrows(IOException::class.java) {
                runBlocking {
                    UserImageOrphanReconciler(context, database.exerciseDao()).reconcile()
                }
            }
        }

        assertTrue(Files.isSymbolicLink(imageRoot.toPath()))
        assertEquals("keep external bytes", externalImage.readText())
    }

    private fun withIsolatedStorage(block: suspend (Context, File) -> Unit) = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "gymup-image-device-test-${UUID.randomUUID()}")
        check(root.mkdirs())
        // Reconciler uses context.applicationContext.filesDir; override both accessors.
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        try {
            block(isolated, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun withInMemoryDatabase(
        context: Context,
        block: suspend (GymUpDatabase) -> Unit,
    ) {
        val database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}
