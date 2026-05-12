package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Unified-search response shape for `/ocs/v2.php/search/providers/<id>/search`.
 * Wrapped in [com.megamaced.nccollectives.data.api.Envelope] when returned.
 */
@Serializable
data class SearchEnvelopeData(
    val name: String = "",
    val isPaginated: Boolean = false,
    val entries: List<SearchEntryDto> = emptyList(),
)

@Serializable
data class SearchEntryDto(
    val title: String = "",
    /** Highlight excerpt; varies by Nextcloud version. */
    val subline: String? = null,
    /** Path to the page in the Collectives UI; typically contains `fileId=…`. */
    val resourceUrl: String? = null,
    val icon: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)
