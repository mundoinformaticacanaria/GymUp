package com.mundoinformaticacanaria.gymup.core.util

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = "\\p{M}+".toRegex()

fun normalizeName(value: String): String {
    val decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
    return decomposed.replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)
}
