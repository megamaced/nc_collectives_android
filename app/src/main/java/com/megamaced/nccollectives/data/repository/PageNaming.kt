package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.db.entity.PageEntity

/**
 * Folder pages in Collectives are stored as `Readme.md` inside a directory
 * named after the page title; leaf pages are stored as `<title>.md`. A page
 * is a folder iff its filename is exactly `Readme.md`.
 */
internal fun PageEntity.isFolderPage(): Boolean = fileName.equals("Readme.md", ignoreCase = true)

/**
 * Normalise a user-entered title to something safe to use as a filename.
 * Strips filesystem path separators and ASCII control codes, collapses
 * whitespace, refuses empty / `.` / `..`, caps at 250 UTF-8 bytes (leaves
 * room for `.md` under the typical 255-byte filesystem limit).
 */
internal fun sanitiseTitleForFilename(title: String): String {
    val cleaned = title
        .filter { ch -> ch !in FORBIDDEN_FILENAME_CHARS && ch.code >= 0x20 }
        .replace(WHITESPACE_RUN, " ")
        .trim()
    require(cleaned.isNotEmpty()) { "Page title cannot be empty" }
    require(cleaned != "." && cleaned != "..") { "Page title cannot be \".\" or \"..\"" }
    val utf8 = cleaned.toByteArray(Charsets.UTF_8)
    return if (utf8.size <= MAX_FILENAME_BYTES) {
        cleaned
    } else {
        // Truncate on a UTF-8 boundary so we don't slice a multi-byte char.
        String(utf8, 0, MAX_FILENAME_BYTES, Charsets.UTF_8).trimEnd()
    }
}

private val FORBIDDEN_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
private val WHITESPACE_RUN = Regex("\\s+")
private const val MAX_FILENAME_BYTES = 250
