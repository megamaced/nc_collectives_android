package com.megamaced.nccollectives.data.api

import kotlinx.serialization.Serializable

/**
 * Nextcloud OCS responses wrap their payload in `{ "ocs": { "meta": …, "data": … } }`.
 * Endpoints either return [Envelope] directly (for endpoints we control) or, more
 * commonly for the Collectives API, return `data` as a single-key object like
 * `{ "data": { "collectives": [...] } }`. Service interfaces declare the
 * appropriate `Envelope<…>` wrapper and unwrap to the inner type.
 */
@Serializable
data class OcsMeta(
    val status: String,
    val statuscode: Int,
    val message: String? = null,
)

@Serializable
data class Envelope<T>(
    val ocs: EnvelopeBody<T>,
)

@Serializable
data class EnvelopeBody<T>(
    val meta: OcsMeta,
    val data: T,
)
