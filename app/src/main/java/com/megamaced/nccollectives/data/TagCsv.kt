package com.megamaced.nccollectives.data

/**
 * Tag values may legitimately contain commas, so we serialise the list using
 * the Unit Separator control character (U+001F) — never produced by user
 * input. Used by `PageEntity.tagsCsv`, `Mappers.kt`, and Batch 11's tag
 * toggle in the repository.
 */
internal const val TAG_SEP_CHAR: Char = ''
internal const val TAG_SEP_STRING: String = ""

internal fun joinTags(tags: List<String>): String = tags.joinToString(TAG_SEP_STRING)

internal fun splitTags(csv: String): List<String> = if (csv.isEmpty()) emptyList() else csv.split(TAG_SEP_CHAR).filter { it.isNotEmpty() }
