package com.megamaced.nccollectives.data.repository

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
    if (utf8.size <= MAX_FILENAME_BYTES) return cleaned
    // B-32: `String(utf8, 0, MAX_FILENAME_BYTES, UTF_8)` substitutes a
    // U+FFFD when byte N-1 falls inside a multi-byte sequence — garbage
    // tails for CJK / emoji / accented titles. Walk backwards to a
    // UTF-8 lead-byte boundary (any byte not in the 0x80..0xBF
    // continuation range) before decoding so we keep only complete
    // code points.
    var end = MAX_FILENAME_BYTES
    while (end > 0 && (utf8[end].toInt() and 0xC0) == 0x80) {
        end--
    }
    return String(utf8, 0, end, Charsets.UTF_8).trimEnd()
}

private val FORBIDDEN_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
private val WHITESPACE_RUN = Regex("\\s+")
private const val MAX_FILENAME_BYTES = 250
