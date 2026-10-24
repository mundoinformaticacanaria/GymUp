package com.mundoinformaticacanaria.gymup.core.util

fun <T> List<T>.swapPositions(fromIndex: Int, toIndex: Int): List<T> {
    require(fromIndex in indices) { "Posición de origen fuera de rango" }
    require(toIndex in indices) { "Posición de destino fuera de rango" }
    if (fromIndex == toIndex) return this

    return toMutableList().apply {
        val displaced = this[toIndex]
        this[toIndex] = this[fromIndex]
        this[fromIndex] = displaced
    }
}
