package com.mundoinformaticacanaria.gymup.data.repository

import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistory
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistoryExecution
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistorySet
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseSearchMetadata
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.deriveExerciseStatus
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomHistoryRepository(
    private val database: GymUpDatabase,
) : HistoryRepository {
    private val trainingDao = database.trainingDao()
    private val routineDao = database.routineDao()
    private val exerciseDao = database.exerciseDao()

    override fun observeExerciseSearchMetadata(): Flow<Map<String, ExerciseSearchMetadata>> =
        combine(trainingDao.observeRealExerciseUsages(), routineDao.observeRoutineExerciseIds()) { usages, routineIds ->
            val routines = routineIds.toSet()
            val mostRecent = usages.groupBy { it.exerciseId }.mapValues { (_, rows) -> rows.first() }
            (mostRecent.keys + routines).associateWith { exerciseId ->
                val usage = mostRecent[exerciseId]
                ExerciseSearchMetadata(
                    lastExecutionDate = usage?.let { LocalDate.ofEpochDay(it.sessionDateEpochDay) },
                    lastExecutionOrderInDay = usage?.orderInDay,
                    inRoutine = exerciseId in routines,
                )
            }
        }

    override fun observeSessionTypeIds(): Flow<Map<String, String>> =
        trainingDao.observeSessions().map { sessions -> sessions.associate { it.id to it.sessionTypeId } }

    override suspend fun getExerciseHistory(exerciseId: String, limit: Int): ExerciseHistory? = database.withTransaction {
        require(limit > 0) { "El límite debe ser positivo" }
        val master = exerciseDao.getById(exerciseId) ?: return@withTransaction null
        val executions = trainingDao.getValidExerciseExecutions(exerciseId, limit).mapNotNull { item ->
            val session = trainingDao.getSession(item.sessionId) ?: return@mapNotNull null
            val allSets = trainingDao.getSets(item.id)
            val actualSets = allSets.filter { it.actualConfirmed }.map { set ->
                ExerciseHistorySet(
                    position = set.position,
                    loadMode = set.loadMode,
                    actualLoad = set.actualLoad,
                    measurementUnit = set.measurementUnit,
                    actualMeasurement = set.actualMeasurement,
                    rir = set.rir,
                )
            }
            if (actualSets.isEmpty()) return@mapNotNull null
            ExerciseHistoryExecution(
                sessionId = session.id,
                sessionName = session.name,
                date = LocalDate.ofEpochDay(session.sessionDateEpochDay),
                orderInDay = session.orderInDay,
                status = deriveExerciseStatus(allSets.map { it.actualConfirmed }),
                sets = actualSets,
            )
        }
        ExerciseHistory(
            exerciseId = master.id,
            nameEs = master.nameEs,
            nameEn = master.nameEn,
            executions = executions,
        )
    }
}
