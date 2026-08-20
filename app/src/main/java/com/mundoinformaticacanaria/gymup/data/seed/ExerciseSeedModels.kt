package com.mundoinformaticacanaria.gymup.data.seed

import kotlinx.serialization.Serializable

@Serializable
data class ExerciseSeedFile(val schema_version: Int, val exercises: List<ExerciseSeedItem>)

@Serializable
data class ExerciseSeedItem(
    val nombre_es: String,
    val nombre_en: String,
    val grupo_muscular: String,
    val equipo: String? = null,
    val modalidad_carga: String,
    val unidad_medicion: String,
    val rir_obligatorio: Boolean,
    val series_iniciales: Int? = null,
    val carga_inicial: Double? = null,
    val medicion_inicial: Int? = null,
    val descripcion: String? = null,
    val imagenes: List<String> = emptyList(),
)
