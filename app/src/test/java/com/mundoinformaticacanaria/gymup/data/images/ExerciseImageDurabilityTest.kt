package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.io.IOException
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
}
