package com.megamaced.nccollectives.data

/**
 * Shared CSV-of-Long helpers. Used by Room entity ↔ domain mappers and any
 * repository that has to parse a CSV column ad-hoc. Lives next to
 * [TagCsv] so that all CSV-style storage helpers stay co-located.
 */

internal fun String.toLongCsvList(): List<Long> = if (isEmpty()) emptyList() else split(',').mapNotNull { it.trim().toLongOrNull() }

internal fun String.toLongCsvSet(): Set<Long> = toLongCsvList().toSet()

internal fun Iterable<Long>.toLongCsv(): String = joinToString(",")
