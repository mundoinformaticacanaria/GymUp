package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
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
