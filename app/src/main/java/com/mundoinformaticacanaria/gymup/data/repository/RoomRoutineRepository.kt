package com.mundoinformaticacanaria.gymup.data.repository

import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.MasterDataDao
import com.mundoinformaticacanaria.gymup.data.local.RoutineDao
import com.mundoinformaticacanaria.gymup.data.local.RoutineEntity
import com.mundoinformaticacanaria.gymup.data.local.RoutineExerciseEntity
import com.mundoinformaticacanaria.gymup.domain.repository.InactiveExerciseException
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineDetail
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineExercise
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineSummary
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomRoutineRepository(
    private val database: GymUpDatabase,
) : RoutineRepository {
    private val routineDao: RoutineDao = database.routineDao()
    private val exerciseDao: ExerciseDao = database.exerciseDao()
    private val masterDao: MasterDataDao = database.masterDataDao()

    override fun observeRoutines(): Flow<List<RoutineSummary>> =
        routineDao.observeRoutines().map { routines -> routines.map { it.toSummary() } }

    override suspend fun getRoutineDetail(routineId: String): RoutineDetail? = database.withTransaction {
        val routine = routineDao.getById(routineId) ?: return@withTransaction null
        val exercises = routineDao.getExercises(routineId).mapNotNull { link ->
            val exercise = exerciseDao.getById(link.exerciseId) ?: return@mapNotNull null
            RoutineExercise(exercise.id, link.position, exercise.nameEs, exercise.nameEn, exercise.isActive)
        }
        RoutineDetail(routine.toSummary(), exercises)
    }

    override suspend fun saveRoutine(
        routineId: String?,
        name: String,
        suggestedSessionTypeId: String?,
        description: String?,
        orderedExerciseIds: List<String>,
    ): String = database.withTransaction {
        require(name.isNotBlank()) { "El nombre de rutina es obligatorio" }
        require(orderedExerciseIds.size == orderedExerciseIds.distinct().size) {
            "Una rutina no puede contener el mismo ejercicio dos veces"
        }
        if (suggestedSessionTypeId != null) requireNotNull(masterDao.getSessionTypeById(suggestedSessionTypeId))
        val id = routineId ?: UUID.randomUUID().toString()
        val existingRoutine = routineId?.let { requireNotNull(routineDao.getById(it)) }
        val savedRoutine = if (existingRoutine == null) {
            RoutineEntity(
                id = id,
                name = name.trim(),
                suggestedSessionTypeId = suggestedSessionTypeId,
                description = description?.trim()?.takeIf(String::isNotBlank),
            )
        } else {
            existingRoutine.copy(
                name = name.trim(),
                suggestedSessionTypeId = suggestedSessionTypeId,
                description = description?.trim()?.takeIf(String::isNotBlank),
            )
        }
        if (existingRoutine == null) routineDao.insertRoutine(savedRoutine) else routineDao.updateRoutine(savedRoutine)

        val current = routineDao.getExercises(id)
        val currentByExerciseId = current.associateBy { it.exerciseId }
        val addedIds = orderedExerciseIds.filterNot(currentByExerciseId::containsKey)
        addedIds.forEach { exerciseId ->
            val exercise = requireNotNull(exerciseDao.getById(exerciseId))
            if (!exercise.isActive) throw InactiveExerciseException()
        }

        current.forEachIndexed { index, item ->
            routineDao.updateExercise(item.copy(position = -(index + 1)))
        }
        current.filterNot { it.exerciseId in orderedExerciseIds }.forEach { item ->
            routineDao.deleteExercise(item)
        }
        orderedExerciseIds.forEachIndexed { index, exerciseId ->
            val currentItem = currentByExerciseId[exerciseId]
            if (currentItem == null) {
                routineDao.insertExercise(RoutineExerciseEntity(id, exerciseId, index + 1))
            } else {
                routineDao.updateExercise(currentItem.copy(position = index + 1))
            }
        }
        id
    }

    override suspend fun deleteRoutine(routineId: String) {
        val routine = routineDao.getById(routineId) ?: return
        routineDao.deleteRoutine(routine)
    }

    private fun RoutineEntity.toSummary(): RoutineSummary = RoutineSummary(
        id = id,
        name = name,
        suggestedSessionTypeId = suggestedSessionTypeId,
        description = description,
    )
}
