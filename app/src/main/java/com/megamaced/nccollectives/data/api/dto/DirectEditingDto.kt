package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Shapes for the Nextcloud Files `directEditing` OCS API
 * (`/ocs/v2.php/apps/files/api/v1/directEditing`). Used to launch an
 * embedded Nextcloud Text session over WebDAV-stored markdown files —
 * the same path the official Nextcloud Notes Android app uses.
 *
 * Spec landmarks (verified against `nextcloud/server` main, May 2026):
 * - `GET .../directEditing` → capability discovery. Returns the registered
 *   editors and creators; we just need to know if any editor handles
 *   `text/markdown` (Text registers itself under the id "text").
 * - `POST .../directEditing/open` → returns a **one-time signed URL** the
 *   WebView loads directly. The token is short-lived and consumed on
 *   first load — a config-change reload must re-request.
 *
 * See `apps/files/lib/Controller/DirectEditingController.php` upstream.
 */

@Serializable
data class DirectEditingCapabilityEnvelopeData(
    /** Map keyed by editor id (e.g. "text") → editor metadata. */
    val editors: Map<String, DirectEditingEditorDto> = emptyMap(),
    val creators: Map<String, DirectEditingCreatorDto> = emptyMap(),
)

@Serializable
data class DirectEditingEditorDto(
    val id: String = "",
    val name: String = "",
    /** Mimetypes this editor can open — we look for `text/markdown`. */
    val mimetypes: List<String> = emptyList(),
    /** Optional mimetypes (e.g. `text/plain`) — treated the same way. */
    val optionalMimetypes: List<String> = emptyList(),
    val secure: Boolean = false,
)

@Serializable
data class DirectEditingCreatorDto(
    val id: String = "",
    val editor: String = "",
    val name: String = "",
    val extension: String = "",
    val templates: Boolean = false,
    val mimetypes: List<String> = emptyList(),
)

/** Response of `POST .../directEditing/open`. */
@Serializable
data class DirectEditingOpenEnvelopeData(
    /**
     * Fully-qualified Nextcloud URL with a one-shot token in the query
     * string. The WebView loads this verbatim — no further auth header
     * or cookie injection is needed because the token itself authorises
     * the session.
     */
    val url: String = "",
)
