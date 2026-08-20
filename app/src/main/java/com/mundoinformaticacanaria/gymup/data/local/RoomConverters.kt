package com.mundoinformaticacanaria.gymup.data.local

import androidx.room.TypeConverter
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState

class RoomConverters {
    @TypeConverter fun loadModeToString(value: LoadMode): String = value.name
    @TypeConverter fun stringToLoadMode(value: String): LoadMode = LoadMode.valueOf(value)
    @TypeConverter fun measurementUnitToString(value: MeasurementUnit): String = value.name
    @TypeConverter fun stringToMeasurementUnit(value: String): MeasurementUnit = MeasurementUnit.valueOf(value)
    @TypeConverter fun sessionOperationalStateToString(value: SessionOperationalState): String = value.name
    @TypeConverter fun stringToSessionOperationalState(value: String): SessionOperationalState = SessionOperationalState.valueOf(value)
    @TypeConverter fun sessionExecutionResultToString(value: SessionExecutionResult): String = value.name
    @TypeConverter fun stringToSessionExecutionResult(value: String): SessionExecutionResult = SessionExecutionResult.valueOf(value)
}
