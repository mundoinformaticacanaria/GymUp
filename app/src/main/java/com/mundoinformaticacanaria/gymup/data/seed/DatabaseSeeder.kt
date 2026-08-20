package com.mundoinformaticacanaria.gymup.data.seed

import android.content.Context
import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.core.util.normalizeName
import com.mundoinformaticacanaria.gymup.data.local.AppMetadataEntity
import com.mundoinformaticacanaria.gymup.data.local.EquipmentEntity
import com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.MuscleGroupEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionTypeEntity
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json

class DatabaseSeeder(private val context: Context, private val database: GymUpDatabase) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun seedIfNeeded() {
        if (database.metadataDao().getValue(SEED_KEY) == SEED_VERSION) return
        val seed = context.assets.open(SEED_ASSET).bufferedReader().use { json.decodeFromString<ExerciseSeedFile>(it.readText()) }
        validateSeed(seed)

        database.withTransaction {
            if (database.metadataDao().getValue(SEED_KEY) == SEED_VERSION) return@withTransaction

            val sessionTypes = SESSION_TYPES.map { name -> SessionTypeEntity(deterministicId("session-type", name), name, normalizeName(name), isProtectedOther = name == "Otro") }
            val muscleGroups = MUSCLE_GROUPS.map { name -> MuscleGroupEntity(deterministicId("muscle-group", name), name, normalizeName(name)) }
            val equipment = EQUIPMENT.map { name -> EquipmentEntity(deterministicId("equipment", name), name, normalizeName(name)) }

            database.masterDataDao().insertSessionTypes(sessionTypes)
            database.masterDataDao().insertMuscleGroups(muscleGroups)
            database.masterDataDao().insertEquipment(equipment)

            val muscleGroupIds = muscleGroups.associateBy({ it.normalizedName }, { it.id })
            val equipmentIds = equipment.associateBy({ it.normalizedName }, { it.id })
            val exercises = seed.exercises.map { item ->
                val normalizedEs = normalizeName(item.nombre_es)
                val normalizedEn = normalizeName(item.nombre_en)
                ExerciseEntity(
                    id = deterministicId("exercise", normalizedEs),
                    nameEs = item.nombre_es,
                    normalizedNameEs = normalizedEs,
                    nameEn = item.nombre_en,
                    normalizedNameEn = normalizedEn,
                    muscleGroupId = muscleGroupIds.getValue(normalizeName(item.grupo_muscular)),
                    equipmentId = item.equipo?.let { equipmentIds.getValue(normalizeName(it)) },
                    defaultLoadMode = SeedMapper.loadMode(item.modalidad_carga),
                    defaultMeasurementUnit = SeedMapper.measurementUnit(item.unidad_medicion),
                    rirRequired = item.rir_obligatorio,
                    initialSetCount = item.series_iniciales,
                    initialLoad = item.carga_inicial,
                    initialMeasurement = item.medicion_inicial,
                    description = item.descripcion,
                )
            }
            database.exerciseDao().insertAll(exercises)
            database.metadataDao().put(AppMetadataEntity(SEED_KEY, SEED_VERSION))
        }
    }

    private fun validateSeed(seed: ExerciseSeedFile) {
        require(seed.schema_version == 1) { "Unsupported exercise seed schema: ${seed.schema_version}" }
        require(seed.exercises.size == EXPECTED_EXERCISE_COUNT) { "Expected $EXPECTED_EXERCISE_COUNT exercises, found ${seed.exercises.size}" }
        val es = mutableSetOf<String>()
        val en = mutableSetOf<String>()
        val validGroups = MUSCLE_GROUPS.mapTo(mutableSetOf(), ::normalizeName)
        val validEquipment = EQUIPMENT.mapTo(mutableSetOf(), ::normalizeName)
        seed.exercises.forEach { item ->
            val normalizedEs = normalizeName(item.nombre_es)
            val normalizedEn = normalizeName(item.nombre_en)
            require(normalizedEs.isNotBlank() && es.add(normalizedEs)) { "Duplicate/blank Spanish exercise name: ${item.nombre_es}" }
            require(normalizedEn.isNotBlank() && en.add(normalizedEn)) { "Duplicate/blank English exercise name: ${item.nombre_en}" }
            require(normalizeName(item.grupo_muscular) in validGroups) { "Unknown muscle group: ${item.grupo_muscular}" }
            require(item.equipo == null || normalizeName(item.equipo) in validEquipment) { "Unknown equipment: ${item.equipo}" }
            require(item.series_iniciales == null || item.series_iniciales > 0)
            require(item.carga_inicial == null || item.carga_inicial >= 0.0)
            require(item.medicion_inicial == null || item.medicion_inicial >= 0)
            require(item.imagenes.size <= 3)
            SeedMapper.loadMode(item.modalidad_carga)
            SeedMapper.measurementUnit(item.unidad_medicion)
        }
    }

    companion object {
        private const val SEED_ASSET = "exercise_catalog_seed_v1.json"
        private const val SEED_KEY = "exercise_seed_version"
        private const val SEED_VERSION = "1"
        const val EXPECTED_EXERCISE_COUNT = 61
        val SESSION_TYPES = listOf("Fuerza", "Hipertrofia", "Cardio", "Movilidad", "Deporte", "Recuperación", "Otro")
        val MUSCLE_GROUPS = listOf("Pecho", "Espalda", "Hombro", "Bíceps", "Tríceps", "Pierna", "Glúteo", "Gemelos", "Core", "Antebrazo/Agarre")
        val EQUIPMENT = listOf("Mancuernas", "Barra", "Polea", "Máquina", "Discos", "Banco", "Peso corporal", "Bandas elásticas", "Kettlebell", "Otro")
        private fun deterministicId(namespace: String, value: String): String = UUID.nameUUIDFromBytes("$namespace:${normalizeName(value)}".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
