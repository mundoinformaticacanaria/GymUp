package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExerciseImageDurabilityTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("exercise-images").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder(context, database).seedIfNeeded()
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("exercise-images").deleteRecursively()
    }

    @Test
    fun `failed directory sync after rename does not publish Room metadata and allows retry`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val bytes = byteArrayOf(4, 8, 15, 16, 23, 42)
        var observedPublishedFile = false
        val failingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            syncImageDirectory = { directory ->
                if (directory.name == "exercise-images") {
                    observedPublishedFile = observedPublishedFile || directory.listFiles().orEmpty().any { !it.name.endsWith(".tmp") }
                    throw IOException("simulated directory fsync failure")
                }
            },
        )

        val failure = runCatching { failingManager.addUserImage(exercise.id, bytes) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(observedPublishedFile)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
        assertFalse(context.filesDir.resolve("exercise-images").listFiles().orEmpty().any { it.isFile })

        val successfulManager = ExerciseImageManager(context, database.exerciseDao())
        val saved = successfulManager.addUserImage(exercise.id, bytes)
        assertEquals(1, database.exerciseDao().getImages(exercise.id).size)
        assertArrayEquals(bytes, successfulManager.readBytes(saved))
    }

    @Test
    fun `failed parent directory sync aborts before creating image or Room reference`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val failingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            syncImageDirectory = { directory ->
                if (directory == context.filesDir) throw IOException("simulated parent fsync failure")
            },
        )

        val failure = runCatching {
            failingManager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
        assertFalse(context.filesDir.resolve("exercise-images").listFiles().orEmpty().any { it.isFile })
    }

    @Test
    fun `failed file deletion restores Room image reference and permits retry`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val bytes = byteArrayOf(5, 7, 11, 13)
        val normalManager = ExerciseImageManager(context, database.exerciseDao())
        val image = normalManager.addUserImage(exercise.id, bytes)
        val failingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            removeUserImageFile = { false },
        )

        val failure = runCatching { failingManager.deleteUserImage(image.id) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(image.id, database.exerciseDao().getImageById(image.id)?.id)
        assertArrayEquals(bytes, normalManager.readBytes(image))

        normalManager.deleteUserImage(image.id)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
        assertFalse(image.localFile!!.exists())
    }

    @Test
    fun `cancelled file deletion still restores Room image reference`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val bytes = byteArrayOf(2, 4, 6, 8)
        val normalManager = ExerciseImageManager(context, database.exerciseDao())
        val image = normalManager.addUserImage(exercise.id, bytes)
        val failingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            removeUserImageFile = { throw CancellationException("simulated cancellation") },
        )

        val failure = runCatching { failingManager.deleteUserImage(image.id) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(image.id, database.exerciseDao().getImageById(image.id)?.id)
        assertArrayEquals(bytes, normalManager.readBytes(image))
    }

    @Test
    fun `failure after Room image insert compensates metadata before deleting bytes`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val bytes = byteArrayOf(10, 20, 30, 40)
        val manager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            afterImageRowInsert = { entity ->
                assertEquals(entity.id, database.exerciseDao().getImageById(entity.id)?.id)
                throw IOException("simulated failure after committed image row")
            },
        )

        val failure = runCatching { manager.addUserImage(exercise.id, bytes) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
        assertFalse(context.filesDir.resolve("exercise-images").listFiles().orEmpty().any { it.isFile })

        val retry = ExerciseImageManager(context, database.exerciseDao())
        val image = retry.addUserImage(exercise.id, bytes)
        assertArrayEquals(bytes, retry.readBytes(image))
        assertEquals(1, database.exerciseDao().getImages(exercise.id).size)
    }

    @Test
    fun `insert compensation never deletes external image after root symlink swap`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val root = context.filesDir.resolve("exercise-images")
        val parked = context.filesDir.resolve("exercise-images-parked-after-insert")
        val outside = context.filesDir.resolve("outside-root-after-insert").apply { mkdirs() }
        val sentinel = byteArrayOf(91, 92, 93, 94)
        val manager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            afterImageRowInsert = { entity ->
                // The image is already published and Room has accepted the row.
                // Redirect the original pathname to a same-named external file.
                Files.move(root.toPath(), parked.toPath())
                Files.createSymbolicLink(root.toPath(), outside.toPath())
                outside.resolve(entity.storageKey.removePrefix("user:")).writeBytes(sentinel)
                throw IOException("simulated failure after root substitution")
            },
        )

        try {
            val failure = runCatching {
                manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
            assertTrue(Files.isSymbolicLink(root.toPath()))
            assertEquals(1, outside.listFiles().orEmpty().size)
            assertArrayEquals(sentinel, outside.listFiles().single().readBytes())
        } finally {
            if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
            if (parked.exists()) Files.move(parked.toPath(), root.toPath())
            outside.deleteRecursively()
        }

        val retry = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, byteArrayOf(5, 6, 7))
        assertArrayEquals(byteArrayOf(5, 6, 7),
            ExerciseImageManager(context, database.exerciseDao()).readBytes(retry))
    }

    @Test
    fun `cancellation after Room image insert compensates metadata and preserves retry`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val bytes = byteArrayOf(1, 3, 5, 7)
        val manager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            afterImageRowInsert = { throw CancellationException("cancelled after image row insert") },
        )

        val failure = runCatching { manager.addUserImage(exercise.id, bytes) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
        assertFalse(context.filesDir.resolve("exercise-images").listFiles().orEmpty().any { it.isFile })

        val retry = ExerciseImageManager(context, database.exerciseDao())
        val image = retry.addUserImage(exercise.id, bytes)
        assertArrayEquals(bytes, retry.readBytes(image))
    }

    @Test
    fun `deletion error after bytes are removed never recreates a dangling Room row`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val image = manager.addUserImage(exercise.id, byteArrayOf(9, 8, 7))
        val failingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            removeUserImageFile = { target ->
                assertTrue(target.delete())
                throw IOException("failure after file deletion")
            },
        )

        val failure = runCatching { failingManager.deleteUserImage(image.id) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(image.localFile!!.exists())
        assertTrue(database.exerciseDao().getImageById(image.id) == null)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
    }

    @Test
    fun `cancellation after bytes are removed never recreates a dangling Room row`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val image = manager.addUserImage(exercise.id, byteArrayOf(6, 5, 4))
        val failingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            removeUserImageFile = { target ->
                assertTrue(target.delete())
                throw CancellationException("cancelled after file deletion")
            },
        )

        val failure = runCatching { failingManager.deleteUserImage(image.id) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(image.localFile!!.exists())
        assertTrue(database.exerciseDao().getImageById(image.id) == null)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
    }

    @Test
    fun `failed directory sync after image deletion never recreates missing Room reference`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        var failDeletionSync = false
        val manager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            syncImageDirectory = { directory ->
                if (failDeletionSync && directory.name == "exercise-images") {
                    throw IOException("simulated directory sync failure after deletion")
                }
            },
        )
        val image = manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3, 4))
        failDeletionSync = true

        val failure = runCatching { manager.deleteUserImage(image.id) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(image.localFile!!.exists())
        assertTrue(database.exerciseDao().getImageById(image.id) == null)

        failDeletionSync = false
        val retry = manager.addUserImage(exercise.id, byteArrayOf(5, 6, 7))
        assertArrayEquals(byteArrayOf(5, 6, 7), manager.readBytes(retry))
        assertEquals(1, database.exerciseDao().getImages(exercise.id).size)
    }

    @Test
    fun `file deletion reporting success without removing bytes restores Room reference`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val normalManager = ExerciseImageManager(context, database.exerciseDao())
        val image = normalManager.addUserImage(exercise.id, byteArrayOf(3, 1, 4))
        val lyingManager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            removeUserImageFile = { true },
        )

        val failure = runCatching { lyingManager.deleteUserImage(image.id) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(image.localFile!!.exists())
        assertEquals(image.id, database.exerciseDao().getImageById(image.id)?.id)

        normalManager.deleteUserImage(image.id)
        assertFalse(image.localFile!!.exists())
        assertTrue(database.exerciseDao().getImageById(image.id) == null)
    }


    @Test
    fun `swapped image root after Room deletion cannot remove external bytes or lose metadata`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val original = byteArrayOf(2, 4, 8, 16)
        val normalManager = ExerciseImageManager(context, database.exerciseDao())
        val image = normalManager.addUserImage(exercise.id, original)
        val root = context.filesDir.resolve("exercise-images")
        val parked = context.filesDir.resolve("exercise-images-parked-after-delete")
        val outside = context.filesDir.resolve("outside-root-after-delete").apply { mkdirs() }
        val marker = outside.resolve("marker").apply { writeText("untouched") }
        try {
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                afterImageRowDelete = {
                    Files.move(root.toPath(), parked.toPath())
                    Files.createSymbolicLink(root.toPath(), outside.toPath())
                },
            )

            val failure = runCatching { manager.deleteUserImage(image.id) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            // A missing file under the malicious root must not discard the Room reference.
            assertEquals(image.id, database.exerciseDao().getImageById(image.id)?.id)
            assertEquals("untouched", marker.readText())
            assertEquals(listOf("marker"), outside.listFiles().orEmpty().map { it.name })
        } finally {
            if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
            if (parked.exists()) Files.move(parked.toPath(), root.toPath())
            outside.deleteRecursively()
        }
        assertArrayEquals(original, normalManager.readBytes(image))
    }

    @Test
    fun `single image deletion refuses to remove shared storage bytes`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val original = byteArrayOf(2, 7, 1, 8)
        val image = manager.addUserImage(exercise.id, original)
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "duplicate-storage-row", exerciseId = exercise.id, position = 2,
                sourceType = "USER", storageKey = image.storageKey,
                originalSourceUrl = null, author = null, license = null,
            ),
        )

        val failure = runCatching { manager.deleteUserImage(image.id) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(image.id, database.exerciseDao().getImageById(image.id)?.id)
        assertEquals(2, database.exerciseDao().getImages(exercise.id).size)
        assertArrayEquals(original, image.localFile!!.readBytes())
    }

    @Test
    fun `single image deletion rejects traversal before dropping Room row`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val outside = context.filesDir.resolve("outside-image-delete").apply { writeText("preserve") }
        try {
            val row = ExerciseImageEntity(
                id = "unsafe-traversal", exerciseId = exercise.id, position = 1,
                sourceType = "USER", storageKey = "user:../outside-image-delete",
                originalSourceUrl = null, author = null, license = null,
            )
            database.exerciseDao().insertImage(row)

            val failure = runCatching {
                ExerciseImageManager(context, database.exerciseDao()).deleteUserImage(row.id)
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(row.id, database.exerciseDao().getImageById(row.id)?.id)
            assertEquals("preserve", outside.readText())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `single image deletion rejects dangling symlink before dropping Room row`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val directory = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val missing = directory.resolve("missing-target")
        val link = directory.resolve("dangling-single-image")
        Files.createSymbolicLink(link.toPath(), missing.toPath())
        val row = ExerciseImageEntity(
            id = "dangling-single-image", exerciseId = exercise.id, position = 1,
            sourceType = "USER", storageKey = "user:dangling-single-image",
            originalSourceUrl = null, author = null, license = null,
        )
        database.exerciseDao().insertImage(row)

        val failure = runCatching {
            ExerciseImageManager(context, database.exerciseDao()).deleteUserImage(row.id)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(row.id, database.exerciseDao().getImageById(row.id)?.id)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(missing.exists())
    }

    @Test
    fun `single image deletion refuses directory before dropping Room row`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val directory = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val unexpected = directory.resolve("directory-as-image").apply { mkdir() }
        val row = ExerciseImageEntity(
            id = "directory-as-image", exerciseId = exercise.id, position = 1,
            sourceType = "USER", storageKey = "user:directory-as-image",
            originalSourceUrl = null, author = null, license = null,
        )
        database.exerciseDao().insertImage(row)

        val failure = runCatching {
            ExerciseImageManager(context, database.exerciseDao()).deleteUserImage(row.id)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(row.id, database.exerciseDao().getImageById(row.id)?.id)
        assertTrue(unexpected.isDirectory)
    }


    @Test
    fun `temporary symlink injected before image write cannot overwrite outside bytes`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val outside = context.filesDir.resolve("outside-image-write").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        try {
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                beforeUserImageWrite = { temp ->
                    Files.createSymbolicLink(temp.toPath(), outside.toPath())
                },
            )

            val failure = runCatching {
                manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertArrayEquals(byteArrayOf(7, 8, 9), outside.readBytes())
            assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
            assertTrue(context.filesDir.resolve("exercise-images").listFiles().orEmpty().isEmpty())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `same length image corruption after sync cannot publish Room reference`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val original = byteArrayOf(1, 2, 3, 4)
        val corrupted = byteArrayOf(9, 8, 7, 6)
        val manager = ExerciseImageManager(
            context = context,
            exerciseDao = database.exerciseDao(),
            afterUserImageSync = { temporary ->
                // Same size: a length-only check would incorrectly publish these bytes.
                temporary.writeBytes(corrupted)
            },
        )

        val failure = runCatching { manager.addUserImage(exercise.id, original) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
        assertTrue(context.filesDir.resolve("exercise-images").listFiles().orEmpty().isEmpty())

        // A later legitimate write must still succeed without inheriting corrupt data.
        val retry = ExerciseImageManager(context, database.exerciseDao())
        val image = retry.addUserImage(exercise.id, original)
        assertArrayEquals(original, retry.readBytes(image))
    }

    @Test
    fun `temporary symlink swapped after sync cannot become a Room image`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val outside = context.filesDir.resolve("outside-image-after-sync").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        try {
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                afterUserImageSync = { temp ->
                    Files.delete(temp.toPath())
                    Files.createSymbolicLink(temp.toPath(), outside.toPath())
                },
            )

            val failure = runCatching {
                manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertArrayEquals(byteArrayOf(4, 5, 6), outside.readBytes())
            assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
            assertTrue(context.filesDir.resolve("exercise-images").listFiles().orEmpty().isEmpty())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `swapped image root before descriptor cannot write outside private storage`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val root = context.filesDir.resolve("exercise-images")
        val parked = context.filesDir.resolve("exercise-images-parked-before-write")
        val outside = context.filesDir.resolve("outside-root-before-write").apply { mkdirs() }
        val marker = outside.resolve("marker").apply { writeText("untouched") }
        try {
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                beforeUserImageWrite = {
                    Files.move(root.toPath(), parked.toPath())
                    Files.createSymbolicLink(root.toPath(), outside.toPath())
                },
            )
            val failure = runCatching {
                manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
            assertEquals("untouched", marker.readText())
            assertEquals(listOf("marker"), outside.listFiles().orEmpty().map { it.name })
        } finally {
            if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
            if (parked.exists()) Files.move(parked.toPath(), root.toPath())
            outside.deleteRecursively()
        }
    }

    @Test
    fun `swapped image root after fsync cannot publish image or Room reference`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val root = context.filesDir.resolve("exercise-images")
        val parked = context.filesDir.resolve("exercise-images-parked-after-sync")
        val outside = context.filesDir.resolve("outside-root-after-sync").apply { mkdirs() }
        val marker = outside.resolve("marker").apply { writeText("untouched") }
        try {
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                afterUserImageSync = {
                    Files.move(root.toPath(), parked.toPath())
                    Files.createSymbolicLink(root.toPath(), outside.toPath())
                },
            )
            val failure = runCatching {
                manager.addUserImage(exercise.id, byteArrayOf(4, 5, 6))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
            assertEquals("untouched", marker.readText())
            assertEquals(listOf("marker"), outside.listFiles().orEmpty().map { it.name })
        } finally {
            if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
            if (parked.exists()) Files.move(parked.toPath(), root.toPath())
            outside.deleteRecursively()
        }
    }
}
