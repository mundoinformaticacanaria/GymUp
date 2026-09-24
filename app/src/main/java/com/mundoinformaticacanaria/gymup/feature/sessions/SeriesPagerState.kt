package com.mundoinformaticacanaria.gymup.feature.sessions

internal fun resolveSeriesPageIndex(
    previousSetIds: List<String>,
    currentSetIds: List<String>,
    previousPage: Int,
): Int {
    if (currentSetIds.isEmpty()) return 0

    val addedSetIds = currentSetIds.filterNot(previousSetIds::contains)
    if (addedSetIds.isNotEmpty()) return currentSetIds.indexOf(addedSetIds.last())

    val previouslySelectedId = previousSetIds.getOrNull(previousPage)
    val survivingIndex = previouslySelectedId?.let(currentSetIds::indexOf) ?: -1
    if (survivingIndex >= 0) return survivingIndex

    return previousPage.coerceIn(currentSetIds.indices)
}

internal fun seriesPositionText(page: Int, pageCount: Int): String {
    require(pageCount > 0) { "pageCount must be positive" }
    require(page in 0 until pageCount) { "page must identify an existing series" }

    val previousIndicator = if (page > 0) "<  " else ""
    val nextIndicator = if (page < pageCount - 1) "  >" else ""
    return "$previousIndicator Serie ${page + 1} de $pageCount$nextIndicator".trim()
}
