package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.SessionEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionSetEntity
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupPreferences
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataValidationResult
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomBackupDataSourceTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase
    private lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = UserPreferencesRepository(context)
        DatabaseSeeder(context, database).seedIfNeeded()
        preferences.setThemeMode(ThemeMode.DARK)
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("exercise-images").deleteRecursively()
    }

    @Test
    fun `snapshot round trip restores masters sessions sets preferences and custom images`() = runBlocking {
        val dao = database.backupDao()
        val sessionType = dao.getSessionTypes().first()
        val exercise = dao.getExercises().first()
        val sessionId = UUID.randomUUID().toString()
        val sessionExerciseId = UUID.randomUUID().toString()
        val setId = UUID.randomUUID().toString()

        dao.insertSessions(
            listOf(
                SessionEntity(
                    id = sessionId,
                    sessionDateEpochDay = LocalDate.of(2026, 8, 21).toEpochDay(),
                    orderInDay = 1,
                    sessionTypeId = sessionType.id,
                    sessionTypeNameSnapshot = sessionType.name,
                    name = "Sesión backup",
                    isAutoName = false,
                    generalNote = "nota",
                    operationalState = SessionOperationalState.REALIZED,
                    executionResult = SessionExecutionResult.COMPLETED,
                ),
            ),
        )
        dao.insertSessionExercises(
            listOf(
                SessionExerciseEntity(
                    id = sessionExerciseId,
                    sessionId = sessionId,
                    exerciseId = exercise.id,
                    position = 1,
                    exerciseNameEsSnapshot = exercise.nameEs,
                    exerciseNameEnSnapshot = exercise.nameEn,
                    muscleGroupNameSnapshot = "Pecho",
                    equipmentNameSnapshot = null,
                    defaultLoadModeSnapshot = LoadMode.KG_TOTAL,
                    defaultMeasurementUnitSnapshot = MeasurementUnit.REPETITIONS,
                    rirRequiredSnapshot = true,
                    descriptionSnapshot = exercise.description,
                    exerciseRestSeconds = 90,
                    note = null,
                    incompleteReason = null,
                    isFinalized = true,
                ),
            ),
        )
        dao.insertSessionSets(
            listOf(
                SessionSetEntity(
                    id = setId,
                    sessionExerciseId = sessionExerciseId,
                    position = 1,
                    loadMode = LoadMode.KG_TOTAL,
                    measurementUnit = MeasurementUnit.REPETITIONS,
                    targetLoad = 40.0,
                    actualLoad = 42.5,
                    targetMeasurement = 10,
                    actualMeasurement = 10,
                    rir = 1,
                    restOverrideSeconds = null,
                    actualConfirmed = true,
                ),
            ),
        )

        val imageManager = ExerciseImageManager(context, database.exerciseDao())
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val customImage = imageManager.addUserImage(exercise.id, imageBytes)

        val source = RoomBackupDataSource(context, database, preferences)
        val snapshot = source.exportSnapshot()
        val decoded = BackupDataCodec.decode(snapshot.data.decodeToString()).getOrThrow()

        assertEquals(BackupDataValidationResult.Valid, BackupDataCodec.validate(decoded))
        assertEquals(61, decoded.exercises.size)
        assertEquals(1, decoded.sessions.size)
        assertEquals(1, decoded.exerciseImages.size)
        assertEquals(ThemeMode.DARK.name, decoded.preferences.themeMode)
        assertArrayEquals(imageBytes, snapshot.images.getValue(customImage.storageKey))

        dao.deleteSessionSets()
        dao.deleteSessionExercises()
        dao.deleteSessions()
        imageManager.deleteUserImage(customImage.id)
        preferences.setThemeMode(ThemeMode.LIGHT)

        source.replaceAll(snapshot)

        assertEquals(61, dao.getExercises().size)
        assertNotNull(database.trainingDao().getSession(sessionId))
        assertEquals(1, database.trainingDao().getSets(sessionExerciseId).size)
        assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        val restoredImage = imageManager.observeImages(exercise.id).first().single()
        assertArrayEquals(imageBytes, imageManager.readBytes(restoredImage))
        assertTrue(restoredImage.localFile?.isFile == true)
    }

    @Test
    fun `injected import failures restore Room images and DataStore without data loss`() = runBlocking {
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
                    name = "Sesión previa",
                    isAutoName = false,
                    generalNote = null,
                    operationalState = SessionOperationalState.PLANNED,
                    executionResult = SessionExecutionResult.NOT_STARTED,
                ),
            ),
        )
        val imageManager = ExerciseImageManager(context, database.exerciseDao())
        val originalBytes = byteArrayOf(1, 3, 5, 7)
        val image = imageManager.addUserImage(exercise.id, originalBytes)
        val source = RoomBackupDataSource(context, database, preferences)
        val original = source.exportSnapshot()
        val decoded = BackupDataCodec.decode(original.data.decodeToString()).getOrThrow()
        val replacementBytes = byteArrayOf(2, 4, 6, 8)
        val replacement = BackupSnapshot(
            data = BackupDataCodec.encode(
                decoded.copy(
                    sessions = emptyList(),
                    preferences = BackupPreferences(ThemeMode.LIGHT.name),
                ),
            ).encodeToByteArray(),
            images = mapOf(image.storageKey to replacementBytes),
        )

        for (phase in BackupReplacePhase.entries) {
            val failingSource = RoomBackupDataSource(context, database, preferences) { current ->
                if (current == phase) error("Fallo inyectado en $phase")
            }
            val failure = runCatching { failingSource.replaceAll(replacement) }.exceptionOrNull()
            assertTrue("Se esperaba fallo en $phase", failure is IllegalStateException)
            assertNotNull("Room no debe perder la sesión en $phase", database.trainingDao().getSession(sessionId))
            assertEquals(1, dao.getSessions().size)
            assertArrayEquals("La imagen anterior debe sobrevivir a $phase", originalBytes, imageManager.readBytes(image))
            assertEquals("El tema anterior debe sobrevivir a $phase", ThemeMode.DARK, preferences.currentThemeMode())
        }

        // The same payload must still be importable after all failed attempts.
        source.replaceAll(replacement)
        assertEquals(0, dao.getSessions().size)
        assertArrayEquals(replacementBytes, imageManager.readBytes(image))
        assertEquals(ThemeMode.LIGHT, preferences.currentThemeMode())
    }


    @Test
    fun `backup holds Room transaction until custom images are captured`() = runBlocking {
        val dao = database.backupDao()
        val type = dao.getSessionTypes().first()
        val exercise = dao.getExercises().first()
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        val image = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, imageBytes)
        val imageCaptureReached = CompletableDeferred<Unit>()
        val allowImageCapture = CompletableDeferred<Unit>()
        val source = RoomBackupDataSource(
            context,
            database,
            preferences,
            exportAfterRoomRead = {
                imageCaptureReached.complete(Unit)
                allowImageCapture.await()
            },
        )

        val exporting = async(Dispatchers.Default) { source.exportSnapshot() }
        imageCaptureReached.await()
        val sessionId = UUID.randomUUID().toString()
        val writerStarted = CompletableDeferred<Unit>()
        val inserting = async(Dispatchers.Default) {
            writerStarted.complete(Unit)
            dao.insertSessions(
                listOf(
                    SessionEntity(
                        id = sessionId,
                        sessionDateEpochDay = LocalDate.of(2026, 10, 26).toEpochDay(),
                        orderInDay = 1,
                        sessionTypeId = type.id,
                        sessionTypeNameSnapshot = type.name,
                        name = "Sesión concurrente",
                        isAutoName = false,
                        generalNote = null,
                        operationalState = SessionOperationalState.PLANNED,
                        executionResult = SessionExecutionResult.NOT_STARTED,
                    ),
                ),
            )
        }
        writerStarted.await()
        yield()
        try {
            assertTrue("Room writer must wait until the snapshot captures image bytes", !inserting.isCompleted)
        } finally {
            allowImageCapture.complete(Unit)
        }

        val snapshot = exporting.await()
        inserting.await()
        val decoded = BackupDataCodec.decode(snapshot.data.decodeToString()).getOrThrow()
        assertEquals(0, decoded.sessions.size)
        assertEquals(1, dao.getSessions().size)
        assertArrayEquals(imageBytes, snapshot.images.getValue(image.storageKey))
    }


    @Test
    fun `staging sync failure aborts before Room and preserves images and preferences`() = runBlocking {
        val dao = database.backupDao()
        val type = dao.getSessionTypes().first()
        val exercise = dao.getExercises().first()
        val sessionId = UUID.randomUUID().toString()
        dao.insertSessions(
            listOf(
                SessionEntity(
                    id = sessionId,
                    sessionDateEpochDay = LocalDate.of(2026, 10, 27).toEpochDay(),
                    orderInDay = 1,
                    sessionTypeId = type.id,
                    sessionTypeNameSnapshot = type.name,
                    name = "Sesión previa a sync",
                    isAutoName = false,
                    generalNote = null,
                    operationalState = SessionOperationalState.PLANNED,
                    executionResult = SessionExecutionResult.NOT_STARTED,
                ),
            ),
        )
        val imageManager = ExerciseImageManager(context, database.exerciseDao())
        val previousBytes = byteArrayOf(7, 7, 7)
        val image = imageManager.addUserImage(exercise.id, previousBytes)
        val source = RoomBackupDataSource(context, database, preferences)
        val before = source.exportSnapshot()
        val data = BackupDataCodec.decode(before.data.decodeToString()).getOrThrow()
        val replacement = BackupSnapshot(
            data = BackupDataCodec.encode(
                data.copy(
                    sessions = emptyList(),
                    preferences = BackupPreferences(ThemeMode.LIGHT.name),
                ),
            ).encodeToByteArray(),
            images = mapOf(image.storageKey to byteArrayOf(8, 8, 8)),
        )

        var syncCompleted = 0
        val failing = RoomBackupDataSource(
            context,
            database,
            preferences,
            afterStagedImageSync = {
                assertEquals(image.storageKey, it)
                syncCompleted++
                error("Fallo inyectado tras fsync de staging")
            },
        )
        val failure = runCatching { failing.replaceAll(replacement) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, syncCompleted)
        assertNotNull(database.trainingDao().getSession(sessionId))
        assertEquals(1, dao.getSessions().size)
        assertArrayEquals(previousBytes, imageManager.readBytes(image))
        assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        assertTrue(
            "El staging fallido debe eliminarse sin tocar las imágenes activas",
            context.filesDir.listFiles().orEmpty().none {
                it.name.startsWith("exercise-images-import-")
            },
        )

        source.replaceAll(replacement)
        assertEquals(0, dao.getSessions().size)
        assertArrayEquals(byteArrayOf(8, 8, 8), imageManager.readBytes(image))
        assertEquals(ThemeMode.LIGHT, preferences.currentThemeMode())
    }


}
