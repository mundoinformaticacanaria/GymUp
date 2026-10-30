package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserImageOrphanReconcilerTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries().build()
        DatabaseSeeder(context, database).seedIfNeeded()
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun `unreferenced generated image is removed but referenced image remains`() = runBlocking {
        val dir = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val keptName = UUID.randomUUID().toString()
        val kept = dir.resolve(keptName).apply { writeText("keep") }
        val orphan = dir.resolve(UUID.randomUUID().toString()).apply { writeText("orphan") }
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = keptName, exerciseId = database.exerciseDao().getAll().first().id,
                position = 1, sourceType = "USER", storageKey = "user:$keptName",
                originalSourceUrl = null, author = null, license = null,
            ),
        )
        UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
    }

    @Test fun `failed deletion remains retryable`() = runBlocking {
        val orphan = context.filesDir.resolve("exercise-images").apply { mkdirs() }
            .resolve(UUID.randomUUID().toString()).apply { writeText("retry") }
        val failing = UserImageOrphanReconciler(
            context, database.exerciseDao(), removeFile = { false }, syncDirectory = {},
        )
        assertThrows(IOException::class.java) { runBlocking { failing.reconcile() } }
        assertTrue(orphan.exists())
        UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()
        assertFalse(orphan.exists())
    }

    @Test fun `legacy names and pending temporaries survive reconciliation`() = runBlocking {
        val dir = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val legacy = dir.resolve("legacy-photo.jpg").apply { writeText("legacy") }
        val temporary = dir.resolve("." + UUID.randomUUID() + ".tmp").apply { writeText("pending") }
        val orphan = dir.resolve(UUID.randomUUID().toString()).apply { writeText("orphan") }

        UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()

        assertTrue(legacy.exists())
        assertTrue(temporary.exists())
        assertFalse(orphan.exists())
    }

    @Test fun `non regular canonical orphan fails closed without removing it`() = runBlocking {
        val dir = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val unexpectedDirectory = dir.resolve(UUID.randomUUID().toString()).apply { mkdirs() }
        val nested = unexpectedDirectory.resolve("valuable.txt").apply { writeText("keep") }

        try {
            assertThrows(IOException::class.java) {
                runBlocking {
                    UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()
                }
            }
            assertTrue(unexpectedDirectory.isDirectory)
            assertTrue(nested.exists())
        } finally {
            unexpectedDirectory.deleteRecursively()
        }
    }

    @Test fun `canonical orphan symlink is rejected without following its external target`() = runBlocking {
        val dir = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val external = context.filesDir.resolve("external-" + UUID.randomUUID()).apply {
            writeText("preserve external bytes")
        }
        val link = dir.resolve(UUID.randomUUID().toString())
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), external.toPath())
            assertThrows(IOException::class.java) {
                runBlocking {
                    UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()
                }
            }
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
            assertTrue(external.readText() == "preserve external bytes")
        } finally {
            java.nio.file.Files.deleteIfExists(link.toPath())
            external.delete()
        }
    }

    @Test fun `linked image root fails closed without traversing an external directory`() = runBlocking {
        val root = context.filesDir.resolve("exercise-images")
        val parked = context.filesDir.resolve("parked-images-" + UUID.randomUUID())
        val external = context.filesDir.resolve("external-images-" + UUID.randomUUID())
            .apply { mkdirs() }
        val valuable = external.resolve(UUID.randomUUID().toString())
            .apply { writeText("external image bytes") }
        val hadRoot = java.nio.file.Files.exists(
            root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        try {
            if (hadRoot) java.nio.file.Files.move(root.toPath(), parked.toPath())
            java.nio.file.Files.createSymbolicLink(root.toPath(), external.toPath())
            assertThrows(IOException::class.java) {
                runBlocking {
                    UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()
                }
            }
            assertTrue(java.nio.file.Files.isSymbolicLink(root.toPath()))
            assertTrue(valuable.readText() == "external image bytes")
        } finally {
            java.nio.file.Files.deleteIfExists(root.toPath())
            if (hadRoot) java.nio.file.Files.move(parked.toPath(), root.toPath())
            valuable.delete()
            external.delete()
        }
    }


    @Test fun `symlinked private parent fails closed and preserves external canonical images`() = runBlocking {
        val outside = context.cacheDir.resolve("external-parent-" + UUID.randomUUID())
            .apply { check(mkdirs()) }
        val images = outside.resolve("exercise-images").apply { check(mkdirs()) }
        val valuable = images.resolve(UUID.randomUUID().toString())
            .apply { writeText("external image bytes must survive") }
        val linkedParent = context.cacheDir.resolve("linked-files-" + UUID.randomUUID())
        try {
            java.nio.file.Files.createSymbolicLink(linkedParent.toPath(), outside.toPath())
            val linkedContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): java.io.File = linkedParent
            }
            assertThrows(IOException::class.java) {
                runBlocking {
                    UserImageOrphanReconciler(
                        linkedContext, database.exerciseDao(), syncDirectory = {},
                    ).reconcile()
                }
            }
            assertTrue(java.nio.file.Files.isSymbolicLink(linkedParent.toPath()))
            assertTrue(valuable.readText() == "external image bytes must survive")
        } finally {
            java.nio.file.Files.deleteIfExists(linkedParent.toPath())
            valuable.delete()
            images.delete()
            outside.delete()
        }
    }

    @Test fun `failed metadata sync is surfaced and retry is idempotent`() = runBlocking {
        val orphan = context.filesDir.resolve("exercise-images").apply { mkdirs() }
            .resolve(UUID.randomUUID().toString()).apply { writeText("fsync") }
        val failing = UserImageOrphanReconciler(
            context, database.exerciseDao(), syncDirectory = { throw IOException("fsync") },
        )
        assertThrows(IOException::class.java) { runBlocking { failing.reconcile() } }
        assertFalse(orphan.exists())
        UserImageOrphanReconciler(context, database.exerciseDao(), syncDirectory = {}).reconcile()
    }
}
