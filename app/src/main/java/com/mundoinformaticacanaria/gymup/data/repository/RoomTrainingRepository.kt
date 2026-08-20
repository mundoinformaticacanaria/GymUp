package com.mundoinformaticacanaria.gymup.data.repository

import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.MasterDataDao
import com.mundoinformaticacanaria.gymup.data.local.RoutineDao
import com.mundoinformaticacanaria.gymup.data.local.RoutineEntity
import com.mundoinformaticacanaria.gymup.data.local.RoutineExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionSetEntity
import com.mundoinformaticacanaria.gymup.data.local.TrainingDao
import com.mundoinformaticacanaria.gymup.domain.repository.DuplicateExerciseException
import com.mundoinformaticacanaria.gymup.domain.repository.InactiveExerciseException
import com.mundoinformaticacanaria.gymup.domain.repository.MissingRirException
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineDetail
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineExercise
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineSummary
import com.mundoinformaticacanaria.gymup.domain.repository.SessionCreationResult
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingExercise
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingSet
import com.mundoinformaticacanaria.gymup.domain.usecase.buildAutoSessionName
import com.mundoinformaticacanaria.gymup.domain.usecase.deriveExerciseStatus
import com.mundoinformaticacanaria.gymup.domain.usecase.deriveSessionExecutionResult
import com.mundoinformaticacanaria.gymup.domain.usecase.hasRealData
import com.mundoinformaticacanaria.gymup.domain.usecase.validateRirValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

class RoomTrainingRepository(
    private val database: GymUpDatabase,
) : TrainingRepository {
    private val trainingDao: TrainingDao = database.trainingDao()
    private val routineDao: RoutineDao = database.routineDao()
    private val exerciseDao: ExerciseDao = database.exerciseDao()
    private val masterDao: MasterDataDao = database.masterDataDao()

    override fun observeSessions(): Flow<List<SessionSummary>> =
        trainingDao.observeSessions().map { sessions -> sessions.map(SessionEntity::toSummary) }

    override fun observeSessionsForDate(date: LocalDate): Flow<List<SessionSummary>> =
        trainingDao.observeSessionsForDate(date.toEpochDay()).map { sessions -> sessions.map(SessionEntity::toSummary) }

    override fun observeRoutines(): Flow<List<RoutineSummary>> =
        routineDao.observeRoutines().map { routines -> routines.map(RoutineEntity::toSummary) }

    override suspend fun getSessionDetail(sessionId: String): SessionDetail? = database.withTransaction {
        val session = trainingDao.getSession(sessionId) ?: return@withTransaction null
        val exercises = trainingDao.getSessionExercises(session.id).map { sessionExercise ->
            val sets = trainingDao.getSets(sessionExercise.id)
            sessionExercise.toDomain(sets)
        }
        SessionDetail(
            summary = session.toSummary(),
            generalNote = session.generalNote,
            isAutoName = session.isAutoName,
            sessionTypeId = session.sessionTypeId,
            exercises = exercises,
        )
    }

    override suspend fun getRoutineDetail(routineId: String): RoutineDetail? = database.withTransaction {
        val routine = routineDao.getById(routineId) ?: return@withTransaction null
        val exercises = routineDao.getExercises(routineId).mapNotNull { link ->
            val exercise = exerciseDao.getById(link.exerciseId) ?: return@mapNotNull null
            RoutineExercise(exercise.id, link.position, exercise.nameEs, exercise.nameEn, exercise.isActive)
        }
        RoutineDetail(routine.toSummary(), exercises)
    }

    override suspend fun createSession(
        date: LocalDate,
        sessionTypeId: String,
        name: String?,
        note: String?,
        source: SessionSource,
    ): SessionCreationResult = database.withTransaction {
        val type = requireNotNull(masterDao.getSessionTypeById(sessionTypeId)) { "Tipo de sesión inexistente" }
        require(type.isActive) { "El tipo de sesión está desactivado" }
        val epochDay = date.toEpochDay()
        val order = trainingDao.maxOrderInDay(epochDay) + 1
        val isAutoName = name.isNullOrBlank()
        val id = UUID.randomUUID().toString()
        trainingDao.insertSession(
            SessionEntity(
                id = id,
                sessionDateEpochDay = epochDay,
                orderInDay = order,
                sessionTypeId = type.id,
                sessionTypeNameSnapshot = type.name,
                name = if (isAutoName) buildAutoSessionName(date, order) else name!!.trim(),
                isAutoName = isAutoName,
                generalNote = note?.trim()?.takeIf(String::isNotBlank),
                operationalState = SessionOperationalState.PLANNED,
                executionResult = SessionExecutionResult.NOT_STARTED,
            ),
        )

        val sourceExercises = when (source) {
            SessionSource.Empty -> emptyList()
            is SessionSource.Routine -> routineDao.getExercises(source.routineId).map { link ->
                val exercise = exerciseDao.getById(link.exerciseId)
                SourceExercise(link.exerciseId, exercise?.nameEs ?: link.exerciseId)
            }
            is SessionSource.Duplicate -> trainingDao.getSessionExercises(source.sessionId).map { item ->
                SourceExercise(item.exerciseId, item.exerciseNameEsSnapshot)
            }
        }

        val omitted = mutableListOf<String>()
        sourceExercises.forEach { sourceExercise ->
            val exercise = exerciseDao.getById(sourceExercise.exerciseId)
            if (exercise == null || !exercise.isActive) {
                omitted += sourceExercise.displayName
            } else {
                addExerciseInternal(id, exercise)
            }
        }
        SessionCreationResult(id, omitted)
    }

    override suspend fun deleteSession(sessionId: String) = database.withTransaction {
        val session = trainingDao.getSession(sessionId) ?: return@withTransaction
        val date = session.sessionDateEpochDay
        trainingDao.deleteSession(session)
        compactSessionOrders(date)
    }

    override suspend fun updateSessionMetadata(sessionId: String, sessionTypeId: String, name: String?, note: String?) = database.withTransaction {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        val type = requireNotNull(masterDao.getSessionTypeById(sessionTypeId))
        require(type.isActive || type.id == session.sessionTypeId) { "El tipo de sesión está desactivado" }
        val isAuto = name.isNullOrBlank()
        val date = LocalDate.ofEpochDay(session.sessionDateEpochDay)
        trainingDao.updateSession(
            session.copy(
                sessionTypeId = type.id,
                sessionTypeNameSnapshot = type.name,
                name = if (isAuto) buildAutoSessionName(date, session.orderInDay) else name!!.trim(),
                isAutoName = isAuto,
                generalNote = note?.trim()?.takeIf(String::isNotBlank),
            ),
        )
    }

    override suspend fun changeSessionPosition(sessionId: String, date: LocalDate, orderInDay: Int) = database.withTransaction {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        val oldEpoch = session.sessionDateEpochDay
        val newEpoch = date.toEpochDay()
        if (oldEpoch == newEpoch) {
            val current = trainingDao.getSessionsForDate(oldEpoch).filterNot { it.id == sessionId }.toMutableList()
            val index = (orderInDay - 1).coerceIn(0, current.size)
            current.add(index, session)
            assignSessionOrder(current, oldEpoch)
        } else {
            trainingDao.updateSession(session.copy(orderInDay = -100000))
            compactSessionOrders(oldEpoch)
            val destination = trainingDao.getSessionsForDate(newEpoch).toMutableList()
            val index = (orderInDay - 1).coerceIn(0, destination.size)
            destination.add(index, session.copy(sessionDateEpochDay = newEpoch, orderInDay = -100000))
            assignSessionOrder(destination, newEpoch)
        }
    }

    override suspend fun setOperationalState(sessionId: String, state: SessionOperationalState) {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        trainingDao.updateSession(session.copy(operationalState = state))
    }

    override suspend fun recalculateObjectives(sessionId: String) = database.withTransaction {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        val sessionExercises = trainingDao.getSessionExercises(sessionId)
        val hasActual = sessionExercises.any { exercise -> trainingDao.getSets(exercise.id).any { it.actualConfirmed } }
        require(!hasActual) { "No se pueden recalcular objetivos masivamente después de iniciar la ejecución" }
        sessionExercises.forEach { sessionExercise ->
            trainingDao.deleteSetsForExercise(sessionExercise.id)
            val exercise = requireNotNull(exerciseDao.getById(sessionExercise.exerciseId))
            buildInitialSets(session, sessionExercise.id, exercise).let { sets ->
                if (sets.isNotEmpty()) trainingDao.insertSets(sets)
            }
        }
        recalculateSession(sessionId)
    }

    override suspend fun addExercise(sessionId: String, exerciseId: String) = database.withTransaction {
        val exercise = requireNotNull(exerciseDao.getById(exerciseId))
        if (!exercise.isActive) throw InactiveExerciseException()
        addExerciseInternal(sessionId, exercise)
    }

    override suspend fun deleteExercise(sessionExerciseId: String) = database.withTransaction {
        val item = requireNotNull(trainingDao.getSessionExercise(sessionExerciseId))
        val sessionId = item.sessionId
        trainingDao.deleteSessionExercise(item)
        compactExercisePositions(sessionId)
        recalculateSession(sessionId)
    }

    override suspend fun reorderExercises(sessionId: String, orderedSessionExerciseIds: List<String>) = database.withTransaction {
        val existing = trainingDao.getSessionExercises(sessionId)
        require(existing.map { it.id }.toSet() == orderedSessionExerciseIds.toSet()) { "Orden de ejercicios inválido" }
        existing.forEachIndexed { index, item -> trainingDao.updateSessionExercise(item.copy(position = -(index + 1))) }
        orderedSessionExerciseIds.forEachIndexed { index, id ->
            val item = existing.first { it.id == id }
            trainingDao.updateSessionExercise(item.copy(position = index + 1))
        }
    }

    override suspend fun updateExerciseMeta(sessionExerciseId: String, restSeconds: Int?, note: String?, incompleteReason: String?) {
        require(restSeconds == null || restSeconds >= 0)
        val item = requireNotNull(trainingDao.getSessionExercise(sessionExerciseId))
        trainingDao.updateSessionExercise(
            item.copy(
                exerciseRestSeconds = restSeconds,
                note = note?.trim()?.takeIf(String::isNotBlank),
                incompleteReason = incompleteReason?.trim()?.takeIf(String::isNotBlank),
            ),
        )
    }

    override suspend fun finalizeExercise(sessionExerciseId: String) = database.withTransaction {
        val item = requireNotNull(trainingDao.getSessionExercise(sessionExerciseId))
        validateMissingRir(item, trainingDao.getSets(item.id))
        trainingDao.updateSessionExercise(item.copy(isFinalized = true))
        recalculateSession(item.sessionId)
    }

    override suspend fun addSet(sessionExerciseId: String) = database.withTransaction {
        val exercise = requireNotNull(trainingDao.getSessionExercise(sessionExerciseId))
        val current = trainingDao.getSets(sessionExerciseId)
        val last = current.lastOrNull()
        trainingDao.insertSet(
            SessionSetEntity(
                id = UUID.randomUUID().toString(),
                sessionExerciseId = sessionExerciseId,
                position = current.size + 1,
                loadMode = last?.loadMode ?: exercise.defaultLoadModeSnapshot,
                measurementUnit = last?.measurementUnit ?: exercise.defaultMeasurementUnitSnapshot,
                targetLoad = last?.targetLoad,
                actualLoad = null,
                targetMeasurement = last?.targetMeasurement,
                actualMeasurement = null,
                rir = null,
                restOverrideSeconds = last?.restOverrideSeconds,
                actualConfirmed = false,
            ),
        )
        recalculateSession(exercise.sessionId)
    }

    override suspend fun deleteSet(setId: String) = database.withTransaction {
        val set = requireNotNull(trainingDao.getSet(setId))
        val sessionExercise = requireNotNull(trainingDao.getSessionExercise(set.sessionExerciseId))
        trainingDao.deleteSet(set)
        compactSetPositions(set.sessionExerciseId)
        recalculateSession(sessionExercise.sessionId)
    }

    override suspend fun updateSetActual(setId: String, actualLoad: Double?, actualMeasurement: Int?, rir: Int?) = database.withTransaction {
        require(actualLoad == null || actualLoad >= 0.0)
        require(actualMeasurement == null || actualMeasurement >= 0)
        validateRirValue(rir)
        val set = requireNotNull(trainingDao.getSet(setId))
        val updated = set.copy(
            actualLoad = actualLoad,
            actualMeasurement = actualMeasurement,
            rir = rir,
            actualConfirmed = hasRealData(actualLoad, actualMeasurement, rir),
        )
        trainingDao.updateSet(updated)
        val sessionExercise = requireNotNull(trainingDao.getSessionExercise(set.sessionExerciseId))
        recalculateSession(sessionExercise.sessionId)
    }

    override suspend fun updateSetTargets(
        setId: String,
        targetLoad: Double?,
        targetMeasurement: Int?,
        loadMode: LoadMode,
        measurementUnit: MeasurementUnit,
    ) {
        require(targetLoad == null || targetLoad >= 0.0)
        require(targetMeasurement == null || targetMeasurement >= 0)
        val set = requireNotNull(trainingDao.getSet(setId))
        trainingDao.updateSet(set.copy(targetLoad = targetLoad, targetMeasurement = targetMeasurement, loadMode = loadMode, measurementUnit = measurementUnit))
    }

    override suspend fun updateSetRest(setId: String, restOverrideSeconds: Int?) {
        require(restOverrideSeconds == null || restOverrideSeconds >= 0)
        val set = requireNotNull(trainingDao.getSet(setId))
        trainingDao.updateSet(set.copy(restOverrideSeconds = restOverrideSeconds))
    }

    override suspend fun fulfillSet(setId: String) = database.withTransaction {
        val set = requireNotNull(trainingDao.getSet(setId))
        val updated = set.copy(
            actualLoad = set.targetLoad,
            actualMeasurement = set.targetMeasurement,
            actualConfirmed = hasRealData(set.targetLoad, set.targetMeasurement, set.rir),
        )
        trainingDao.updateSet(updated)
        val sessionExercise = requireNotNull(trainingDao.getSessionExercise(set.sessionExerciseId))
        recalculateSession(sessionExercise.sessionId)
    }

    override suspend fun finalizeSession(sessionId: String) = database.withTransaction {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        val exercises = trainingDao.getSessionExercises(sessionId)
        val missing = exercises.flatMap { exercise ->
            val sets = trainingDao.getSets(exercise.id)
            if (exercise.rirRequiredSnapshot) sets.filter { it.actualConfirmed && it.rir == null }.map { it.id } else emptyList()
        }
        if (missing.isNotEmpty()) throw MissingRirException(missing)
        exercises.filterNot { it.isFinalized }.forEach { trainingDao.updateSessionExercise(it.copy(isFinalized = true)) }
        val result = calculateSessionResult(sessionId)
        trainingDao.updateSession(session.copy(operationalState = SessionOperationalState.REALIZED, executionResult = result))
    }

    override suspend fun createRoutine(name: String, suggestedSessionTypeId: String?, description: String?): String {
        require(name.isNotBlank()) { "El nombre de rutina es obligatorio" }
        if (suggestedSessionTypeId != null) requireNotNull(masterDao.getSessionTypeById(suggestedSessionTypeId))
        val id = UUID.randomUUID().toString()
        routineDao.insertRoutine(RoutineEntity(id, name.trim(), suggestedSessionTypeId, description?.trim()?.takeIf(String::isNotBlank)))
        return id
    }

    override suspend fun updateRoutine(routineId: String, name: String, suggestedSessionTypeId: String?, description: String?) {
        require(name.isNotBlank())
        if (suggestedSessionTypeId != null) requireNotNull(masterDao.getSessionTypeById(suggestedSessionTypeId))
        val routine = requireNotNull(routineDao.getById(routineId))
        routineDao.updateRoutine(routine.copy(name = name.trim(), suggestedSessionTypeId = suggestedSessionTypeId, description = description?.trim()?.takeIf(String::isNotBlank)))
    }

    override suspend fun deleteRoutine(routineId: String) {
        val routine = routineDao.getById(routineId) ?: return
        routineDao.deleteRoutine(routine)
    }

    override suspend fun addRoutineExercise(routineId: String, exerciseId: String) = database.withTransaction {
        requireNotNull(routineDao.getById(routineId))
        val exercise = requireNotNull(exerciseDao.getById(exerciseId))
        if (!exercise.isActive) throw InactiveExerciseException()
        val current = routineDao.getExercises(routineId)
        if (current.any { it.exerciseId == exerciseId }) throw DuplicateExerciseException()
        routineDao.insertExercise(RoutineExerciseEntity(routineId, exerciseId, current.size + 1))
    }

    override suspend fun deleteRoutineExercise(routineId: String, exerciseId: String) = database.withTransaction {
        val item = routineDao.getExercises(routineId).firstOrNull { it.exerciseId == exerciseId } ?: return@withTransaction
        routineDao.deleteExercise(item)
        compactRoutinePositions(routineId)
    }

    override suspend fun reorderRoutineExercises(routineId: String, orderedExerciseIds: List<String>) = database.withTransaction {
        val current = routineDao.getExercises(routineId)
        require(current.map { it.exerciseId }.toSet() == orderedExerciseIds.toSet())
        current.forEachIndexed { index, item -> routineDao.updateExercise(item.copy(position = -(index + 1))) }
        orderedExerciseIds.forEachIndexed { index, id ->
            routineDao.updateExercise(current.first { it.exerciseId == id }.copy(position = index + 1))
        }
    }

    private suspend fun addExerciseInternal(sessionId: String, exercise: ExerciseEntity) {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        if (trainingDao.getSessionExerciseByExercise(sessionId, exercise.id) != null) throw DuplicateExerciseException()
        val current = trainingDao.getSessionExercises(sessionId)
        val muscleGroup = requireNotNull(masterDao.getMuscleGroupById(exercise.muscleGroupId))
        val equipment = exercise.equipmentId?.let { masterDao.getEquipmentById(it) }
        val id = UUID.randomUUID().toString()
        val sessionExercise = SessionExerciseEntity(
            id = id,
            sessionId = sessionId,
            exerciseId = exercise.id,
            position = current.size + 1,
            exerciseNameEsSnapshot = exercise.nameEs,
            exerciseNameEnSnapshot = exercise.nameEn,
            muscleGroupNameSnapshot = muscleGroup.name,
            equipmentNameSnapshot = equipment?.name,
            defaultLoadModeSnapshot = exercise.defaultLoadMode,
            defaultMeasurementUnitSnapshot = exercise.defaultMeasurementUnit,
            rirRequiredSnapshot = exercise.rirRequired,
            descriptionSnapshot = exercise.description,
            exerciseRestSeconds = null,
            note = null,
            incompleteReason = null,
            isFinalized = false,
        )
        trainingDao.insertSessionExercise(sessionExercise)
        val sets = buildInitialSets(session, id, exercise)
        if (sets.isNotEmpty()) trainingDao.insertSets(sets)
    }

    private suspend fun buildInitialSets(session: SessionEntity, sessionExerciseId: String, exercise: ExerciseEntity): List<SessionSetEntity> {
        val prior = trainingDao.getPriorExerciseExecutions(exercise.id, session.sessionDateEpochDay, session.orderInDay)
            .firstNotNullOfOrNull { candidate ->
                val sets = trainingDao.getSets(candidate.id)
                if (sets.isNotEmpty() && sets.all { it.actualConfirmed }) sets else null
            }
        if (prior != null) {
            return prior.mapIndexed { index, previous ->
                SessionSetEntity(
                    id = UUID.randomUUID().toString(),
                    sessionExerciseId = sessionExerciseId,
                    position = index + 1,
                    loadMode = previous.loadMode,
                    measurementUnit = previous.measurementUnit,
                    targetLoad = previous.actualLoad,
                    actualLoad = null,
                    targetMeasurement = previous.actualMeasurement,
                    actualMeasurement = null,
                    rir = null,
                    restOverrideSeconds = previous.restOverrideSeconds,
                    actualConfirmed = false,
                )
            }
        }
        val count = exercise.initialSetCount ?: 0
        return (1..count).map { position ->
            SessionSetEntity(
                id = UUID.randomUUID().toString(),
                sessionExerciseId = sessionExerciseId,
                position = position,
                loadMode = exercise.defaultLoadMode,
                measurementUnit = exercise.defaultMeasurementUnit,
                targetLoad = exercise.initialLoad,
                actualLoad = null,
                targetMeasurement = exercise.initialMeasurement,
                actualMeasurement = null,
                rir = null,
                restOverrideSeconds = null,
                actualConfirmed = false,
            )
        }
    }

    private suspend fun recalculateSession(sessionId: String) {
        val session = requireNotNull(trainingDao.getSession(sessionId))
        val result = calculateSessionResult(sessionId)
        val hasActual = result != SessionExecutionResult.NOT_STARTED
        val nextState = if (session.operationalState == SessionOperationalState.PLANNED && hasActual) SessionOperationalState.IN_PROGRESS else session.operationalState
        trainingDao.updateSession(session.copy(operationalState = nextState, executionResult = result))
    }

    private suspend fun calculateSessionResult(sessionId: String): SessionExecutionResult {
        val statuses = trainingDao.getSessionExercises(sessionId).map { exercise ->
            deriveExerciseStatus(trainingDao.getSets(exercise.id).map { it.actualConfirmed })
        }
        return deriveSessionExecutionResult(statuses)
    }

    private fun validateMissingRir(exercise: SessionExerciseEntity, sets: List<SessionSetEntity>) {
        if (!exercise.rirRequiredSnapshot) return
        val missing = sets.filter { it.actualConfirmed && it.rir == null }.map { it.id }
        if (missing.isNotEmpty()) throw MissingRirException(missing)
    }

    private suspend fun compactSessionOrders(epochDay: Long) {
        val sessions = trainingDao.getSessionsForDate(epochDay)
        assignSessionOrder(sessions, epochDay)
    }

    private suspend fun assignSessionOrder(sessions: List<SessionEntity>, epochDay: Long) {
        sessions.forEachIndexed { index, item -> trainingDao.updateSession(item.copy(sessionDateEpochDay = epochDay, orderInDay = -(index + 1))) }
        sessions.forEachIndexed { index, original ->
            val order = index + 1
            val date = LocalDate.ofEpochDay(epochDay)
            val parked = original.copy(sessionDateEpochDay = epochDay, orderInDay = -(index + 1))
            trainingDao.updateSession(
                parked.copy(
                    orderInDay = order,
                    name = if (original.isAutoName) buildAutoSessionName(date, order) else original.name,
                ),
            )
        }
    }

    private suspend fun compactExercisePositions(sessionId: String) {
        val items = trainingDao.getSessionExercises(sessionId)
        items.forEachIndexed { index, item -> trainingDao.updateSessionExercise(item.copy(position = -(index + 1))) }
        items.forEachIndexed { index, item -> trainingDao.updateSessionExercise(item.copy(position = index + 1)) }
    }

    private suspend fun compactSetPositions(sessionExerciseId: String) {
        val sets = trainingDao.getSets(sessionExerciseId)
        sets.forEachIndexed { index, item -> trainingDao.updateSet(item.copy(position = -(index + 1))) }
        sets.forEachIndexed { index, item -> trainingDao.updateSet(item.copy(position = index + 1)) }
    }

    private suspend fun compactRoutinePositions(routineId: String) {
        val items = routineDao.getExercises(routineId)
        items.forEachIndexed { index, item -> routineDao.updateExercise(item.copy(position = -(index + 1))) }
        items.forEachIndexed { index, item -> routineDao.updateExercise(item.copy(position = index + 1)) }
    }

    private fun SessionEntity.toSummary(): SessionSummary = SessionSummary(
        id = id,
        date = LocalDate.ofEpochDay(sessionDateEpochDay),
        orderInDay = orderInDay,
        name = name,
        sessionTypeName = sessionTypeNameSnapshot,
        operationalState = operationalState,
        executionResult = executionResult,
    )

    private fun SessionExerciseEntity.toDomain(sets: List<SessionSetEntity>): TrainingExercise = TrainingExercise(
        id = id,
        exerciseId = exerciseId,
        position = position,
        nameEs = exerciseNameEsSnapshot,
        nameEn = exerciseNameEnSnapshot,
        muscleGroupName = muscleGroupNameSnapshot,
        equipmentName = equipmentNameSnapshot,
        loadMode = defaultLoadModeSnapshot,
        measurementUnit = defaultMeasurementUnitSnapshot,
        rirRequired = rirRequiredSnapshot,
        description = descriptionSnapshot,
        exerciseRestSeconds = exerciseRestSeconds,
        note = note,
        incompleteReason = incompleteReason,
        isFinalized = isFinalized,
        status = deriveExerciseStatus(sets.map { it.actualConfirmed }),
        sets = sets.map(SessionSetEntity::toDomain),
    )

    private fun SessionSetEntity.toDomain(): TrainingSet = TrainingSet(
        id = id,
        position = position,
        loadMode = loadMode,
        measurementUnit = measurementUnit,
        targetLoad = targetLoad,
        actualLoad = actualLoad,
        targetMeasurement = targetMeasurement,
        actualMeasurement = actualMeasurement,
        rir = rir,
        restOverrideSeconds = restOverrideSeconds,
        actualConfirmed = actualConfirmed,
    )

    private fun RoutineEntity.toSummary(): RoutineSummary = RoutineSummary(id, name, suggestedSessionTypeId, description)

    private data class SourceExercise(val exerciseId: String, val displayName: String)
}
