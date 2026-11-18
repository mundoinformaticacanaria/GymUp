package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Guards both Room metadata and private bytes, not only the DAO operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExerciseImageWriteBarrierIntegrationTest {
    @Test
    fun imageCreationWaitsUntilExclusiveBackupReleasesBarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            DatabaseSeeder(context, database).seedIfNeeded()
            val exerciseId = database.backupDao().getExercises().first().id
            val barrier = BackupDomainWriteBarrier()
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                domainWriteBarrier = barrier,
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backup = launch {
                barrier.withExclusiveBackup {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val writer = launch {
                manager.addUserImage(exerciseId, byteArrayOf(1, 2, 3, 4))
            }
            yield()
            assertFalse(writer.isCompleted)
            assertTrue(database.exerciseDao().getImages(exerciseId).isEmpty())
            assertFalse(context.filesDir.resolve("exercise-images").listFiles().orEmpty().any { it.isFile })

            release.complete(Unit)
            backup.join()
            writer.join()
            assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
        } finally {
            database.close()
            context.filesDir.resolve("exercise-images").deleteRecursively()
        }
    }

    @Test
    fun imageDeletionWaitsUntilExclusiveBackupReleasesBarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            DatabaseSeeder(context, database).seedIfNeeded()
            val exerciseId = database.backupDao().getExercises().first().id
            val barrier = BackupDomainWriteBarrier()
            val manager = ExerciseImageManager(
                context = context,
                exerciseDao = database.exerciseDao(),
                domainWriteBarrier = barrier,
            )
            val image = manager.addUserImage(exerciseId, byteArrayOf(9, 8, 7))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backup = launch {
                barrier.withExclusiveBackup {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val writer = launch { manager.deleteUserImage(image.id) }
            yield()
            assertFalse(writer.isCompleted)
            assertEquals(image.id, database.exerciseDao().getImageById(image.id)?.id)
            assertTrue(image.localFile?.isFile == true)

            release.complete(Unit)
            backup.join()
            writer.join()
            assertTrue(database.exerciseDao().getImages(exerciseId).isEmpty())
            assertFalse(image.localFile?.exists() == true)
        } finally {
            database.close()
            context.filesDir.resolve("exercise-images").deleteRecursively()
        }
    }
}
