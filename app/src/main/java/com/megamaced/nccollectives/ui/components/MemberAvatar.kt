package com.megamaced.nccollectives.ui.components

import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.HostInterceptor
import okhttp3.HttpUrl

/*
 * Avatar URL building and monogram fallback for Team members, shared by the
 * members strip on a collective's landing page and the members screen
 * (R-65).
 *
 * Both surfaces render the same people from the same endpoint, and they were
 * each written with their own copy. The copies disagreed in a way that
 * mattered: one percent-encoded the `userId` (`%40`) while the other left
 * `@` literal, so the same person's avatar was fetched and cached under two
 * different keys. One implementation, one cache entry.
 */

/** Shown when a label yields no letter or digit at all. */
private const val MONOGRAM_FALLBACK = "?"

/**
 * Word separators for [monogramOf]. Space-, dot-, underscore- and
 * hyphen-separated names all read as multi-word, which covers both display
 * names ("David Mace") and login-shaped ids ("david.mace", "service_account").
 */
private val MONOGRAM_SEPARATORS = Regex("[\\s._\\-]+")

/**
 * Pixel size asked of the server, not the layout size. 128 covers a 40 dp
 * slot up to ~3x density without asking every phone for the 512 px original.
 * Fixed rather than derived from the display: Nextcloud caches avatars per
 * requested size, so one number keeps both that cache and Coil's keyed on a
 * single entry per user.
 */
internal const val AVATAR_REQUEST_PX = 128

/**
 * Initials for [label], used whenever an avatar cannot be shown — the member
 * has uploaded none, the request is still in flight, or the endpoint answered
 * with its JSON 404.
 *
 * Email-shaped labels are reduced to their local part first, so
 * `david.mace@example.com` reads "DM" rather than borrowing letters from the
 * domain. Walks code points rather than chars: an astral-plane initial would
 * otherwise render as half a surrogate pair.
 */
internal fun monogramOf(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return MONOGRAM_FALLBACK
    val local = trimmed.substringBefore('@').ifBlank { trimmed }
    val glyphs = local.split(MONOGRAM_SEPARATORS).mapNotNull(::leadingAlphanumeric)
    return when (glyphs.size) {
        0 -> MONOGRAM_FALLBACK
        1 -> glyphs.first().uppercase()
        else -> (glyphs.first() + glyphs.last()).uppercase()
    }
}

/** First letter-or-digit code point of [word] as a whole string, or null. */
private fun leadingAlphanumeric(word: String): String? {
    var i = 0
    while (i < word.length) {
        val codePoint = word.codePointAt(i)
        if (Character.isLetterOrDigit(codePoint)) return String(Character.toChars(codePoint))
        i += Character.charCount(codePoint)
    }
    return null
}

/**
 * Avatar URL for [userId], or null when the id cannot safely become a path
 * segment.
 *
 * S-27: `userId` is server-supplied, so it goes through the same
 * [ServerStringValidation.cleanPathSegment] gate as every other server string
 * that becomes a path (S-14′) — rejecting `..`, separators and control
 * characters outright — and is then added via `addPathSegment`, which encodes
 * what remains. Built on Retrofit's placeholder host rather than the stored
 * one so the UI layer needs no credentials: `HostInterceptor` recognises it
 * as app-built, splices any subdirectory prefix, and stamps the
 * `RequestOrigin` tag that `AuthInterceptor` requires before it will sign a
 * request (S-23).
 */
internal fun memberAvatarUrl(
    userId: String,
    sizePx: Int = AVATAR_REQUEST_PX,
): String? {
    val segment = ServerStringValidation.cleanPathSegment(userId) ?: return null
    return HttpUrl
        .Builder()
        .scheme("https")
        .host(HostInterceptor.PLACEHOLDER_HOST)
        .addPathSegment("index.php")
        .addPathSegment("avatar")
        .addPathSegment(segment)
        .addPathSegment(sizePx.toString())
        .build()
        .toString()
}
