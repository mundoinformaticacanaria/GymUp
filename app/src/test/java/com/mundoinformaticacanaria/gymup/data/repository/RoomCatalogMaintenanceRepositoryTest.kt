package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionResult
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRemovalConfirmationRequired
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomCatalogMaintenanceRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase
    private lateinit var maintenance: RoomCatalogMaintenanceRepository
    private lateinit var training: RoomSessionRepository
    private lateinit var routines: RoomRoutineRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder(context, database).seedIfNeeded()
        maintenance = RoomCatalogMaintenanceRepository(context, database)
        training = RoomSessionRepository(database)
        routines = RoomRoutineRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("exercise-images").deleteRecursively()
    }

    @Test
    fun `protected Other session type cannot be renamed or deactivated`() = runBlocking {
        val other = database.masterDataDao().getSessionTypes().single { it.isProtectedOther }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.renameMaster(MasterCatalogKind.SESSION_TYPE, other.id, "Otro nombre") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.deactivateMaster(MasterCatalogKind.SESSION_TYPE, other.id) }
        }
        Unit
    }

    @Test
    fun `editing an exercise preserves an already inactive master reference`() = runBlocking {
        val exercise = database.exerciseDao().getAll().first()
        maintenance.deactivateMaster(MasterCatalogKind.MUSCLE_GROUP, exercise.muscleGroupId)

        maintenance.updateExercise(
            exercise.id,
            exercise.toInput(description = "Descripción actualizada"),
        )

        val updated = requireNotNull(maintenance.getExercise(exercise.id))
        assertEquals(exercise.muscleGroupId, updated.muscleGroupId)
        assertEquals("Descripción actualizada", updated.description)
    }

    @Test
    fun `exercise initial configuration persists and populates a planned session`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(
            customInput(
                groupId = group.id,
                es = "Press prueba defaults",
                en = "Defaults test press",
                initialSetCount = 3,
                initialLoad = 20.0,
                initialMeasurement = 10,
            ),
        )

        val saved = requireNotNull(maintenance.getExercise(exerciseId))
        assertEquals(3, saved.initialSetCount)
        assertEquals(20.0, saved.initialLoad ?: -1.0, 0.0)
        assertEquals(10, saved.initialMeasurement)

        val sessionType = database.masterDataDao().getSessionTypes().first { it.isActive }
        val sessionId = training.createSession(
            LocalDate.of(2026, 8, 21),
            sessionType.id,
            null,
            null,
            SessionSource.Empty,
        ).sessionId
        training.addExercise(sessionId, exerciseId)

        val sets = requireNotNull(training.getSessionDetail(sessionId)).exercises.single().sets
        assertEquals(3, sets.size)
        sets.forEach { set ->
            assertEquals(20.0, set.targetLoad ?: -1.0, 0.0)
            assertEquals(10, set.targetMeasurement)
        }
    }

    @Test
    fun `exercise in routines requires confirmation and is removed atomically`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Ejercicio temporal", "Temporary exercise"))
        val routineId = routines.saveRoutine(null, "Rutina", null, null, listOf(exerciseId))

        assertThrows(RoutineRemovalConfirmationRequired::class.java) {
            runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = false) }
        }

        val result = maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true)
        assertEquals(ExerciseDeletionResult.Deleted(1), result)
        assertNull(maintenance.getExercise(exerciseId))
        assertEquals(emptyList<String>(), requireNotNull(routines.getRoutineDetail(routineId)).exercises.map { it.exerciseId })
    }

    @Test
    fun `exercise referenced by a session is deactivated instead of physically deleted`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Ejercicio histórico", "Historical exercise"))
        val sessionType = database.masterDataDao().getSessionTypes().first { it.isActive }
        val sessionId = training.createSession(
            LocalDate.of(2026, 8, 21),
            sessionType.id,
            null,
            null,
            SessionSource.Empty,
        ).sessionId
        training.addExercise(sessionId, exerciseId)

        val result = maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = false)

        assertEquals(ExerciseDeletionResult.Deactivated, result)
        assertFalse(requireNotNull(maintenance.getExercise(exerciseId)).isActive)
        assertEquals(exerciseId, requireNotNull(training.getSessionDetail(sessionId)).exercises.single().exerciseId)
    }

    @Test
    fun `exercise deletion reports failed image removal instead of silently succeeding`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Borrado fallido", "Failed deletion"))
        val file = context.filesDir.resolve("exercise-images").apply { mkdirs() }.resolve("delete-failure")
        file.writeText("image bytes")
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "delete-failure", exerciseId = exerciseId, position = 1,
                sourceType = "USER", storageKey = "user:delete-failure",
                originalSourceUrl = null, author = null, license = null,
            ),
        )
        val failing = RoomCatalogMaintenanceRepository(
            context, database, removeImageFile = { false }, syncImageDirectory = {},
        )

        assertThrows(IOException::class.java) {
            runBlocking { failing.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
        }
        // This is NOT rollback: the Room transaction already committed. Preserve the
        // orphan bytes for a future recovery/cleanup instead of reporting false success.
        assertNull(maintenance.getExercise(exerciseId))
        assertTrue(file.isFile)
    }

    @Test
    fun `exercise deletion rejects false positive filesystem success`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Borrado simulado", "Simulated deletion"))
        val file = context.filesDir.resolve("exercise-images").apply { mkdirs() }.resolve("false-success")
        file.writeText("image bytes")
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "false-success", exerciseId = exerciseId, position = 1,
                sourceType = "USER", storageKey = "user:false-success",
                originalSourceUrl = null, author = null, license = null,
            ),
        )
        val failing = RoomCatalogMaintenanceRepository(
            context, database, removeImageFile = { true }, syncImageDirectory = {},
        )

        assertThrows(IOException::class.java) {
            runBlocking { failing.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
        }
        assertNull(maintenance.getExercise(exerciseId))
        assertTrue(file.isFile)
    }

    @Test
    fun `exercise deletion rejects traversal image key before committing Room`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Imagen insegura", "Unsafe image"))
        val outside = context.filesDir.resolve("outside-exercise-images").apply { writeText("keep me") }
        try {
            database.exerciseDao().insertImage(
                ExerciseImageEntity(
                    id = "traversal", exerciseId = exerciseId, position = 1,
                    sourceType = "USER", storageKey = "user:../outside-exercise-images",
                    originalSourceUrl = null, author = null, license = null,
                ),
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
            }

            assertTrue(maintenance.getExercise(exerciseId) != null)
            assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
            assertEquals("keep me", outside.readText())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `exercise deletion rejects malformed user image key without dropping references`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Imagen inválida", "Invalid image"))
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "missing-prefix", exerciseId = exerciseId, position = 1,
                sourceType = "USER", storageKey = "legacy-image-key",
                originalSourceUrl = null, author = null, license = null,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
        }

        assertTrue(maintenance.getExercise(exerciseId) != null)
        assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
    }

    @Test
    fun `exercise deletion refuses a directory masquerading as a user image before Room commit`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Imagen directorio", "Directory image"))
        val imageDirectory = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val unexpectedDirectory = imageDirectory.resolve("not-an-image").apply { mkdir() }
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "not-an-image", exerciseId = exerciseId, position = 1,
                sourceType = "USER", storageKey = "user:not-an-image",
                originalSourceUrl = null, author = null, license = null,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
        }

        assertTrue(maintenance.getExercise(exerciseId) != null)
        assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
        assertTrue(unexpectedDirectory.isDirectory)
    }

    @Test
    fun `exercise deletion rejects a dangling symbolic link before committing Room`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Enlace roto", "Dangling link"))
        val imageDirectory = context.filesDir.resolve("exercise-images").apply { mkdirs() }
        val missingTarget = imageDirectory.resolve("missing-image-target")
        val danglingLink = imageDirectory.resolve("dangling-image")
        Files.createSymbolicLink(danglingLink.toPath(), missingTarget.toPath())
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = "dangling-image", exerciseId = exerciseId, position = 1,
                sourceType = "USER", storageKey = "user:dangling-image",
                originalSourceUrl = null, author = null, license = null,
            ),
        )

        assertFalse(danglingLink.exists()) // File.exists follows the missing target.
        assertTrue(Files.isSymbolicLink(danglingLink.toPath()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
        }

        assertTrue(maintenance.getExercise(exerciseId) != null)
        assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
        assertTrue(Files.isSymbolicLink(danglingLink.toPath()))
        assertFalse(missingTarget.exists())
    }

    @Test
    fun `exercise deletion rejects symlinked image root without deleting external files`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Raíz enlazada", "Linked image root"))
        val root = context.filesDir.resolve("exercise-images")
        val outside = context.filesDir.resolve("outside-image-root").apply { mkdirs() }
        val externalImage = outside.resolve("external-image").apply { writeText("keep me") }
        root.deleteRecursively()
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        try {
            database.exerciseDao().insertImage(
                ExerciseImageEntity(
                    id = "external-image", exerciseId = exerciseId, position = 1,
                    sourceType = "USER", storageKey = "user:external-image",
                    originalSourceUrl = null, author = null, license = null,
                ),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
            }
            assertTrue(maintenance.getExercise(exerciseId) != null)
            assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
            assertEquals("keep me", externalImage.readText())
            assertTrue(Files.isSymbolicLink(root.toPath()))
        } finally {
            Files.deleteIfExists(root.toPath())
            outside.deleteRecursively()
        }
    }

    @Test
    fun `exercise deletion rejects dangling image root before committing Room`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Raíz colgante", "Dangling image root"))
        val root = context.filesDir.resolve("exercise-images")
        val missing = context.filesDir.resolve("missing-image-root")
        root.deleteRecursively()
        Files.createSymbolicLink(root.toPath(), missing.toPath())
        try {
            database.exerciseDao().insertImage(
                ExerciseImageEntity(
                    id = "missing-image", exerciseId = exerciseId, position = 1,
                    sourceType = "USER", storageKey = "user:missing-image",
                    originalSourceUrl = null, author = null, license = null,
                ),
            )
            assertTrue(Files.isSymbolicLink(root.toPath()))
            assertFalse(root.exists())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true) }
            }
            assertTrue(maintenance.getExercise(exerciseId) != null)
            assertEquals(1, database.exerciseDao().getImages(exerciseId).size)
        } finally {
            Files.deleteIfExists(root.toPath())
        }
    }

    private fun customInput(
        groupId: String,
        es: String,
        en: String,
        initialSetCount: Int? = null,
        initialLoad: Double? = null,
        initialMeasurement: Int? = null,
    ) = ExerciseMaintenanceInput(
        nameEs = es,
        nameEn = en,
        muscleGroupId = groupId,
        equipmentId = null,
        loadMode = LoadMode.KG_TOTAL,
        measurementUnit = MeasurementUnit.REPETITIONS,
        rirRequired = true,
        initialSetCount = initialSetCount,
        initialLoad = initialLoad,
        initialMeasurement = initialMeasurement,
        description = null,
    )

    private fun com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity.toInput(description: String?) =
        ExerciseMaintenanceInput(
            nameEs = nameEs,
            nameEn = nameEn,
            muscleGroupId = muscleGroupId,
            equipmentId = equipmentId,
            loadMode = defaultLoadMode,
            measurementUnit = defaultMeasurementUnit,
            rirRequired = rirRequired,
            initialSetCount = initialSetCount,
            initialLoad = initialLoad,
            initialMeasurement = initialMeasurement,
            description = description,
        )
}
