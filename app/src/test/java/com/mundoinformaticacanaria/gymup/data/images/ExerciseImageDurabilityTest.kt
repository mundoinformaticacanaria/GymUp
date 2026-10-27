package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.io.IOException
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
}
