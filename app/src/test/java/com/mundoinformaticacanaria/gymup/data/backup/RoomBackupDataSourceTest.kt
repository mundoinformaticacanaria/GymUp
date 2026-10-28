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
import java.io.File
import java.nio.file.Files
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
    fun `backup export rejects symlinked user image without reading external bytes`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val image = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, byteArrayOf(1, 2, 3))
        val imageFile = requireNotNull(image.localFile)
        val outside = File(context.filesDir, "external-image-${UUID.randomUUID()}")
        val sentinel = byteArrayOf(91, 92, 93)
        outside.writeBytes(sentinel)
        assertTrue(imageFile.delete())
        Files.createSymbolicLink(imageFile.toPath(), outside.toPath())

        try {
            val failure = runCatching {
                RoomBackupDataSource(context, database, preferences).exportSnapshot()
            }.exceptionOrNull()
            assertTrue("An export must not follow a user-image symlink", failure is IllegalStateException)
            assertArrayEquals(sentinel, outside.readBytes())
            assertTrue(database.exerciseDao().getImages(exercise.id).any { it.id == image.id })
        } finally {
            Files.deleteIfExists(imageFile.toPath())
            outside.delete()
        }
    }

    @Test
    fun `backup export rejects image swapped to symlink after preflight`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val image = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, byteArrayOf(1, 2, 3))
        val imageFile = requireNotNull(image.localFile)
        val outside = File(context.filesDir, "external-racing-image-${UUID.randomUUID()}")
        val sentinel = byteArrayOf(81, 82, 83)
        outside.writeBytes(sentinel)
        val source = RoomBackupDataSource(
            context,
            database,
            preferences,
            beforeRequiredImageRead = { file ->
                assertEquals(imageFile.absolutePath, file.absolutePath)
                assertTrue(file.delete())
                Files.createSymbolicLink(file.toPath(), outside.toPath())
            },
        )
        try {
            val failure = runCatching { source.exportSnapshot() }.exceptionOrNull()
            assertTrue("Export must reject a symlink swapped after validation", failure is IllegalStateException)
            assertArrayEquals(sentinel, outside.readBytes())
            assertTrue(database.exerciseDao().getImages(exercise.id).any { it.id == image.id })
            assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        } finally {
            Files.deleteIfExists(imageFile.toPath())
            outside.delete()
        }
    }

    @Test
    fun `backup export rejects oversized persisted image without changing Room or theme`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val image = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, byteArrayOf(1, 2, 3))
        val file = requireNotNull(image.localFile)
        // A persisted file can be corrupted or replaced after the 10 MiB import limit.
        // Use a sparse file so this regression does not allocate the oversized payload.
        java.io.RandomAccessFile(file, "rw").use { it.setLength(10L * 1024L * 1024L + 1L) }

        val failure = runCatching {
            RoomBackupDataSource(context, database, preferences).exportSnapshot()
        }.exceptionOrNull()
        assertTrue("An oversized pre-image must abort backup export", failure is IllegalStateException)
        assertTrue(failure?.message?.contains("10 MiB") == true)
        assertTrue(database.exerciseDao().getImages(exercise.id).any { it.id == image.id })
        assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        assertEquals(10L * 1024L * 1024L + 1L, file.length())
    }

    @Test
    fun `backup export rejects dangling user image symlink without changing Room`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val image = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, byteArrayOf(4, 5, 6))
        val imageFile = requireNotNull(image.localFile)
        assertTrue(imageFile.delete())
        Files.createSymbolicLink(
            imageFile.toPath(),
            File(context.filesDir, "missing-image-${UUID.randomUUID()}").toPath(),
        )

        try {
            val failure = runCatching {
                RoomBackupDataSource(context, database, preferences).exportSnapshot()
            }.exceptionOrNull()
            assertTrue("A dangling symlink must not be treated as an image", failure is IllegalStateException)
            assertTrue(database.exerciseDao().getImages(exercise.id).any { it.id == image.id })
        } finally {
            Files.deleteIfExists(imageFile.toPath())
        }
    }


    @Test
    fun `backup import rejects symlinked image root before changing Room or theme`() = runBlocking {
        val source = RoomBackupDataSource(context, database, preferences)
        val before = source.exportSnapshot()
        val replacementData = BackupDataCodec.decode(before.data.decodeToString()).getOrThrow()
            .copy(preferences = BackupPreferences(ThemeMode.LIGHT.name))
        val replacement = BackupSnapshot(
            data = BackupDataCodec.encode(replacementData).encodeToByteArray(),
            images = before.images,
        )
        val root = File(context.filesDir, "exercise-images")
        val outside = File(context.filesDir, "external-images-${UUID.randomUUID()}")
        assertTrue(root.deleteRecursively())
        assertTrue(outside.mkdirs())
        val sentinel = File(outside, "sentinel")
        sentinel.writeBytes(byteArrayOf(42, 43))
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        try {
            val failure = runCatching { source.replaceAll(replacement) }.exceptionOrNull()
            assertTrue("A symlinked image root must abort before Room mutation", failure is IllegalStateException)
            assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
            assertArrayEquals(byteArrayOf(42, 43), sentinel.readBytes())
            assertTrue(Files.isSymbolicLink(root.toPath()))
        } finally {
            Files.deleteIfExists(root.toPath())
            outside.deleteRecursively()
        }
        assertArrayEquals(before.data, source.exportSnapshot().data)
    }

    @Test
    fun `backup import rejects image root replaced by symlink before activation`() = runBlocking {
        val source = RoomBackupDataSource(context, database, preferences)
        val before = source.exportSnapshot()
        val replacementData = BackupDataCodec.decode(before.data.decodeToString()).getOrThrow()
            .copy(preferences = BackupPreferences(ThemeMode.LIGHT.name))
        val replacement = BackupSnapshot(
            data = BackupDataCodec.encode(replacementData).encodeToByteArray(),
            images = before.images,
        )
        val root = File(context.filesDir, "exercise-images")
        val outside = File(context.filesDir, "external-images-${UUID.randomUUID()}")
        assertTrue(root.deleteRecursively())
        assertTrue(outside.mkdirs())
        val sentinel = File(outside, "sentinel")
        sentinel.writeBytes(byteArrayOf(51, 52))
        val racingSource = RoomBackupDataSource(
            context,
            database,
            preferences,
            importFault = { phase ->
                if (phase == BackupReplacePhase.BEFORE_IMAGES_ACTIVATED) {
                    Files.createSymbolicLink(root.toPath(), outside.toPath())
                }
            },
        )
        try {
            val failure = runCatching { racingSource.replaceAll(replacement) }.exceptionOrNull()
            assertTrue("A root swapped after staging must abort the import", failure is IllegalStateException)
            assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
            assertArrayEquals(byteArrayOf(51, 52), sentinel.readBytes())
            assertTrue(Files.isSymbolicLink(root.toPath()))
        } finally {
            Files.deleteIfExists(root.toPath())
            outside.deleteRecursively()
        }
        assertArrayEquals(before.data, source.exportSnapshot().data)
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
            val failingSource = RoomBackupDataSource(
                context, database, preferences,
                importFault = { current ->
                    if (current == phase) error("Fallo inyectado en $phase")
                },
            )
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



    @Test
    fun `second staged image failure leaves all active images and Room untouched`() = runBlocking {
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
                    name = "Sesión anterior a staging múltiple",
                    isAutoName = false,
                    generalNote = null,
                    operationalState = SessionOperationalState.PLANNED,
                    executionResult = SessionExecutionResult.NOT_STARTED,
                ),
            ),
        )
        val imageManager = ExerciseImageManager(context, database.exerciseDao())
        val first = imageManager.addUserImage(exercise.id, byteArrayOf(1, 1))
        val second = imageManager.addUserImage(exercise.id, byteArrayOf(2, 2))
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
            images = mapOf(
                first.storageKey to byteArrayOf(3, 3),
                second.storageKey to byteArrayOf(4, 4),
            ),
        )

        var stagedCount = 0
        val failing = RoomBackupDataSource(
            context,
            database,
            preferences,
            afterStagedImageSync = {
                stagedCount++
                if (stagedCount == 2) error("Fallo inyectado en segunda imagen staged")
            },
        )
        val failure = runCatching { failing.replaceAll(replacement) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(2, stagedCount)
        assertNotNull(database.trainingDao().getSession(sessionId))
        assertEquals(1, dao.getSessions().size)
        assertArrayEquals(byteArrayOf(1, 1), imageManager.readBytes(first))
        assertArrayEquals(byteArrayOf(2, 2), imageManager.readBytes(second))
        assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        assertTrue(
            "El staging parcial no debe quedar en disco",
            context.filesDir.listFiles().orEmpty().none {
                it.name.startsWith("exercise-images-import-")
            },
        )

        source.replaceAll(replacement)
        assertEquals(0, dao.getSessions().size)
        assertArrayEquals(byteArrayOf(3, 3), imageManager.readBytes(first))
        assertArrayEquals(byteArrayOf(4, 4), imageManager.readBytes(second))
        assertEquals(ThemeMode.LIGHT, preferences.currentThemeMode())
    }

    @Test
    fun `user image sync failure before rename leaves no Room metadata or orphan file`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val before = database.exerciseDao().getImages(exercise.id)
        val directory = context.filesDir.resolve("exercise-images")
        val originalFiles = directory.listFiles().orEmpty().map { it.name }.toSet()
        val bytes = byteArrayOf(11, 22, 33, 44)
        var syncedFileSeen = false
        val failing = ExerciseImageManager(
            context,
            database.exerciseDao(),
            afterUserImageSync = { temporary ->
                assertTrue(temporary.isFile)
                assertArrayEquals(bytes, temporary.readBytes())
                syncedFileSeen = true
                error("Fallo inyectado antes de activar imagen de usuario")
            },
        )

        val failure = runCatching { failing.addUserImage(exercise.id, bytes) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(syncedFileSeen)
        assertEquals(before, database.exerciseDao().getImages(exercise.id))
        assertEquals("No deben quedar temporales ni imágenes publicadas", originalFiles, directory.listFiles().orEmpty().map { it.name }.toSet())

        val successful = ExerciseImageManager(context, database.exerciseDao())
            .addUserImage(exercise.id, bytes)
        assertArrayEquals(bytes, successful.localFile?.readBytes())
        assertEquals(before.size + 1, database.exerciseDao().getImages(exercise.id).size)
    }

    @Test
    fun `directory fsync failures around image activation restore Room images and theme`() = runBlocking {
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
                    name = "Sesión anterior a fsync directorio",
                    isAutoName = false,
                    generalNote = null,
                    operationalState = SessionOperationalState.PLANNED,
                    executionResult = SessionExecutionResult.NOT_STARTED,
                ),
            ),
        )
        val imageManager = ExerciseImageManager(context, database.exerciseDao())
        val previousBytes = byteArrayOf(1, 2, 3)
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
            images = mapOf(image.storageKey to byteArrayOf(9, 8, 7)),
        )

        // Parent sync #1: staging directory publication.
        // Parent sync #2: old images moved aside; #3: new images activated.
        // Both failures must restore the old image directory, including the case
        // where rename succeeded but its directory fsync did not.
        for (failAt in listOf(2, 3)) {
            var parentSyncs = 0
            val failing = RoomBackupDataSource(
                context,
                database,
                preferences,
                imageDirectorySync = { directory ->
                    if (directory.canonicalPath == context.filesDir.canonicalPath) {
                        parentSyncs++
                        if (parentSyncs == failAt) {
                            error("Simulated image directory fsync failure at $failAt")
                        }
                    }
                },
            )
            val failure = runCatching { failing.replaceAll(replacement) }.exceptionOrNull()
            assertTrue("Expected fsync failure at $failAt", failure is IllegalStateException)
            assertNotNull(database.trainingDao().getSession(sessionId))
            assertEquals(1, dao.getSessions().size)
            assertArrayEquals(previousBytes, imageManager.readBytes(image))
            assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
            assertTrue(
                "No stale staged or previous image directory after rollback",
                context.filesDir.listFiles().orEmpty().none {
                    it.name.startsWith("exercise-images-import-") ||
                        it.name.startsWith("exercise-images-previous-")
                },
            )
        }

        source.replaceAll(replacement)
        assertEquals(0, dao.getSessions().size)
        assertArrayEquals(byteArrayOf(9, 8, 7), imageManager.readBytes(image))
        assertEquals(ThemeMode.LIGHT, preferences.currentThemeMode())
    }


    @Test
    fun `staging symlink before open cannot truncate external file or mutate domain`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val image = manager.addUserImage(exercise.id, byteArrayOf(1, 2, 3))
        val original = RoomBackupDataSource(context, database, preferences).exportSnapshot()
        val data = BackupDataCodec.decode(original.data.decodeToString()).getOrThrow()
        val replacement = BackupSnapshot(
            data = BackupDataCodec.encode(
                data.copy(preferences = BackupPreferences(ThemeMode.LIGHT.name)),
            ).encodeToByteArray(),
            images = mapOf(image.storageKey to byteArrayOf(4, 5, 6)),
        )
        val external = File(context.cacheDir, "protected-staging-${UUID.randomUUID()}.bin")
        val protectedBytes = byteArrayOf(91, 92, 93)
        external.writeBytes(protectedBytes)
        try {
            var injected = false
            val unsafe = RoomBackupDataSource(
                context, database, preferences,
                beforeStagedImageWrite = { target ->
                    Files.createSymbolicLink(target.toPath(), external.toPath())
                    injected = true
                },
            )
            val failure = runCatching { unsafe.replaceAll(replacement) }.exceptionOrNull()
            assertTrue("Staging must reject a preexisting symlink", failure != null)
            assertTrue(injected)
            assertArrayEquals(protectedBytes, external.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), manager.readBytes(image))
            assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
            assertEquals(1, database.exerciseDao().getImages(exercise.id).size)
            assertTrue(context.filesDir.listFiles().orEmpty().none {
                it.name.startsWith("exercise-images-import-")
            })
        } finally {
            external.delete()
        }
    }

    @Test
    fun `oversized staged image fails before Room and preserves previous data`() = runBlocking {
        val exercise = database.backupDao().getExercises().first()
        val manager = ExerciseImageManager(context, database.exerciseDao())
        val image = manager.addUserImage(exercise.id, byteArrayOf(7, 8))
        val original = RoomBackupDataSource(context, database, preferences).exportSnapshot()
        val data = BackupDataCodec.decode(original.data.decodeToString()).getOrThrow()
        val replacement = BackupSnapshot(
            data = BackupDataCodec.encode(
                data.copy(preferences = BackupPreferences(ThemeMode.LIGHT.name)),
            ).encodeToByteArray(),
            images = mapOf(image.storageKey to ByteArray(10 * 1024 * 1024 + 1)),
        )
        val failure = runCatching {
            RoomBackupDataSource(context, database, preferences).replaceAll(replacement)
        }.exceptionOrNull()
        assertTrue("Staging must reject images larger than 10 MiB", failure is IllegalArgumentException)
        assertArrayEquals(byteArrayOf(7, 8), manager.readBytes(image))
        assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        assertEquals(1, database.exerciseDao().getImages(exercise.id).size)
        assertTrue(context.filesDir.listFiles().orEmpty().none {
            it.name.startsWith("exercise-images-import-")
        })
    }
}
