package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.SessionEntity
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupPreferences
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRecoverySnapshotAdapterTest {
    @Test
    fun `prepared journal replays real Room images and theme after simulated process restart`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val journalDir = File(context.filesDir, "recovery-test-${UUID.randomUUID()}")
        try {
            DatabaseSeeder(context, database).seedIfNeeded()
            val preferences = UserPreferencesRepository(context)
            preferences.setThemeMode(ThemeMode.DARK)
            val dao = database.backupDao()
            val type = dao.getSessionTypes().first()
            val exercise = dao.getExercises().first()
            val sessionId = UUID.randomUUID().toString()
            dao.insertSessions(
                listOf(
                    SessionEntity(
                        id = sessionId,
                        sessionDateEpochDay = LocalDate.of(2026, 10, 25).toEpochDay(),
                        orderInDay = 1,
                        sessionTypeId = type.id,
                        sessionTypeNameSnapshot = type.name,
                        name = "Sesión antes del corte",
                        isAutoName = false,
                        generalNote = null,
                        operationalState = SessionOperationalState.PLANNED,
                        executionResult = SessionExecutionResult.NOT_STARTED,
                    ),
                ),
            )
            val images = ExerciseImageManager(context, database.exerciseDao())
            val oldBytes = byteArrayOf(1, 3, 5, 7)
            val newBytes = byteArrayOf(2, 4, 6, 8)
            val image = images.addUserImage(exercise.id, oldBytes)
            val source = RoomBackupDataSource(context, database, preferences)
            val adapter = BackupRecoverySnapshotAdapter(source, "1.0-test")
            val previousArchive = adapter.capturePrevious()
            val journal = BackupRecoveryJournal(journalDir)
            journal.prepare(previousArchive)

            val oldData = BackupDataCodec.decode(source.exportSnapshot().data.decodeToString()).getOrThrow()
            val replacement = BackupSnapshot(
                data = BackupDataCodec.encode(
                    oldData.copy(
                        sessions = emptyList(),
                        preferences = BackupPreferences(ThemeMode.LIGHT.name),
                    ),
                ).encodeToByteArray(),
                images = mapOf(image.storageKey to newBytes),
            )
            source.replaceAll(replacement)
            assertEquals(0, dao.getSessions().size)
            assertArrayEquals(newBytes, images.readBytes(image))
            assertEquals(ThemeMode.LIGHT, preferences.currentThemeMode())

            // Simulate death after applying the new data but before marking the journal COMMITTED.
            // The first replay itself is interrupted after restoring data, before journal commit.
            val interruptedReplay = runCatching {
                BackupRecoveryCoordinator(BackupRecoveryJournal(journalDir))
                    .recoverBeforeDataAccess { archive ->
                        adapter.restorePrevious(archive)
                        error("Simulated death after replay, before journal commit")
                    }
            }
            assertTrue(interruptedReplay.isFailure)
            assertTrue(BackupRecoveryJournal(journalDir).inspect() is BackupRecoveryJournal.State.Prepared)

            // A second coordinator must safely replay the same Room/images/theme pre-image again.
            BackupRecoveryCoordinator(BackupRecoveryJournal(journalDir))
                .recoverBeforeDataAccess(adapter::restorePrevious)
            assertNotNull(database.trainingDao().getSession(sessionId))
            assertEquals(1, dao.getSessions().size)
            assertArrayEquals(oldBytes, images.readBytes(image))
            assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
            assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(journalDir).inspect())

            // Reopening after successful recovery must not reapply the old snapshot.
            BackupRecoveryCoordinator(BackupRecoveryJournal(journalDir))
                .recoverBeforeDataAccess { error("No journal should be replayed twice") }
            assertTrue(journalDir.exists().not())
        } finally {
            database.close()
            journalDir.deleteRecursively()
            File(context.filesDir, "exercise-images").deleteRecursively()
        }
    }
}
