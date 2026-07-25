package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Shape of `GET /status.php` — Nextcloud's unauthenticated status endpoint.
 * Not OCS-wrapped: the response is a bare JSON object, so no [Envelope].
 *
 * Only [version] is load-bearing here. It's the full dotted build version
 * (e.g. `34.0.1.2`), which changes on every server upgrade — and since Text
 * is a *bundled* app, its shipped JS/CSS assets can only change when the
 * server version does. That makes this the cheap signal for "the editor
 * assets the WebView cached may be stale", per the upstream Text
 * maintainer's suggestion on GitHub issue #1.
 *
 * Every field is defaulted: `status.php` has gained and lost keys across
 * releases, and a missing one must never fail the parse.
 */
@Serializable
data class ServerStatusDto(
    val installed: Boolean = false,
    val maintenance: Boolean = false,
    val needsDbUpgrade: Boolean = false,
    /** Dotted build version, e.g. `34.0.1.2`. */
    val version: String = "",
    /** Human-readable version, e.g. `34.0.1`. */
    val versionstring: String = "",
    val productname: String = "",
)
