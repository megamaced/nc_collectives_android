package com.megamaced.nccollectives.data

/**
 * Shared CSV-of-Long helpers. Used by Room entity ↔ domain mappers and any
 * repository that has to parse a CSV column ad-hoc. Lives next to
 * [TagCsv] so that all CSV-style storage helpers stay co-located.
 */

internal fun String.toLongCsvList(): List<Long> = if (isEmpty()) emptyList() else split(',').mapNotNull { it.trim().toLongOrNull() }

internal fun String.toLongCsvSet(): Set<Long> = toLongCsvList().toSet()

internal fun Iterable<Long>.toLongCsv(): String = joinToString(",")

/**
 * R-22: encode an `Iterable<Long>` as a JSON-array string (`[1,2,3]`). Both
 * the favorites endpoint (`PUT …/favoritePages`) and the subpage-order
 * endpoint (`PUT …/subpageOrder`) accept a JSON-stringified-in-form-field
 * value with this exact shape — same handful of repos were spelling out
 * the `joinToString(prefix="[", postfix="]", separator=",")` invocation
 * inline. Centralising lets a future round-trip test live alongside it.
 */
internal fun Iterable<Long>.toJsonLongArray(): String = joinToString(prefix = "[", postfix = "]", separator = ",")
