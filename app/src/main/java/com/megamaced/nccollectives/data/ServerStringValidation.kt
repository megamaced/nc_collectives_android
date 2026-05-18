package com.megamaced.nccollectives.data

/**
 * Trust-boundary validation for strings the server hands us that we then
 * splice into request paths or display in the UI. Closes audit items
 * **S-14′** (path traversal in `collectivePath` / `filePath` / `fileName`)
 * and **S-18** (display-string sanitisation for tag names + page titles).
 *
 * The threat model is a misbehaving or compromised Nextcloud — not a hostile
 * client. A malicious upstream that returned e.g. `collectivePath="../../"`
 * would otherwise see the components added verbatim to the WebDAV URL via
 * OkHttp's `addPathSegment` (it percent-encodes `/` inside a segment but
 * keeps `..` intact, so the path resolves up the tree on the server side).
 */
internal object ServerStringValidation {
    /**
     * Cap on the length we'll accept for a single tag name or page title.
     * The Nextcloud schema allows ~256 chars for titles; this leaves room
     * for unicode expansion while keeping the value workable as a route
     * arg / SQL parameter.
     */
    const val MAX_DISPLAY_LEN = 512

    /**
     * Sanitise a user-visible string (page title, tag name, display name).
     * Trims whitespace, strips ASCII control characters (0x00–0x1F + 0x7F)
     * including the tag-CSV separator (U+001F), and truncates to
     * [MAX_DISPLAY_LEN] code points. Multi-line input collapses to a
     * single line — these fields aren't expected to carry newlines and
     * passing one to Compose Navigation as a route arg breaks parsing.
     */
    fun sanitiseDisplay(raw: String): String {
        if (raw.isEmpty()) return raw
        val filtered = buildString(raw.length) {
            for (ch in raw) {
                val code = ch.code
                // Strip C0 controls + DEL. Tabs and newlines are also
                // control chars in this range and we strip them on
                // purpose — a tab in a page title is almost certainly
                // server-side bug or attacker noise.
                if (code in 0x00..0x1F || code == 0x7F) continue
                append(ch)
                if (length >= MAX_DISPLAY_LEN) break
            }
        }
        return filtered.trim()
    }

    /**
     * Validate one URL path segment we're about to splice into a WebDAV
     * request. Returns `null` if the segment is hostile — caller should
     * abort (throwing keeps the failure visible rather than silently
     * normalising the URL into something benign-looking but wrong).
     *
     * Rejects:
     *   - empty / blank
     *   - `.` or `..` (parent / current dir traversal)
     *   - any C0 control char or DEL — these have no business in a path
     *   - embedded `/` or `\\` — caller is responsible for splitting
     *     already; an embedded separator means the upstream string carried
     *     extra structure we don't trust.
     */
    fun cleanPathSegment(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "." || trimmed == "..") return null
        for (ch in trimmed) {
            val code = ch.code
            if (code in 0x00..0x1F || code == 0x7F) return null
            if (ch == '/' || ch == '\\') return null
        }
        return trimmed
    }
}
