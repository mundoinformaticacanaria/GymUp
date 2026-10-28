package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExerciseImageSafeReadTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase

    private fun cleanup() {
        val root = context.filesDir.resolve("exercise-images")
        if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
        else root.deleteRecursively()
        context.filesDir.resolve("image-root-target").deleteRecursively()
        context.filesDir.resolve("outside-gallery").delete()
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        cleanup()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries().build()
        DatabaseSeeder(context, database).seedIfNeeded()
    }

    @After
    fun tearDown() {
        database.close()
        cleanup()
    }

    @Test
    fun linkedRootRejectsImageWrite() = runBlocking {
        val target = context.filesDir.resolve("image-root-target").apply { mkdirs() }
        val root = context.filesDir.resolve("exercise-images")
        Files.createSymbolicLink(root.toPath(), target.toPath())
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())

        val failure = runCatching {
            manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(target.listFiles().isNullOrEmpty())
        assertTrue(database.exerciseDao().getImages(exercise.id).isEmpty())
    }

    @Test
    fun galleryIgnoresTraversalAndForgedOutsideFile() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val outside = context.filesDir.resolve("outside-gallery").apply { writeBytes(byteArrayOf(9, 8)) }
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "unsafe-gallery", exerciseId = exercise.id, position = 1,
                sourceType = "USER", storageKey = "user:../outside-gallery",
                originalSourceUrl = null, author = null, license = null,
            ),
        )
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val item = manager.observeImages(exercise.id).first().single()

        assertNull(item.localFile)
        assertNull(manager.readBytes(item.copy(localFile = outside)))
        assertArrayEquals(byteArrayOf(9, 8), outside.readBytes())
    }

    @Test
    fun galleryAndReadBytesIgnoreSymlinkToOutsideFile() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val outside = context.filesDir.resolve("outside-gallery").apply { writeBytes(byteArrayOf(7, 6)) }
        val root = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val link = root.resolve("linked-gallery")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "linked-gallery", exerciseId = exercise.id, position = 1,
                sourceType = "USER", storageKey = "user:linked-gallery",
                originalSourceUrl = null, author = null, license = null,
            ),
        )
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val item = manager.observeImages(exercise.id).first().single()

        assertNull(item.localFile)
        assertNull(manager.readBytes(item.copy(localFile = link)))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertArrayEquals(byteArrayOf(7, 6), outside.readBytes())
    }

    @Test
    fun regularImageRemainsVisibleAndReadable() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val bytes = byteArrayOf(1, 3, 5, 7)
        val saved = manager.addUserImage(exercise.id, bytes)
        val item = manager.observeImages(exercise.id).first().single()

        assertTrue(item.localFile?.isFile == true)
        assertArrayEquals(bytes, manager.readBytes(item))
        assertArrayEquals(bytes, manager.readBytes(saved))
    }
}
