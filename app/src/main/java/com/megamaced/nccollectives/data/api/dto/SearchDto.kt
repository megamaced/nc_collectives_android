package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /**
     * Nextcloud has shipped `attributes` as either an object (`{ fileId: "…" }`)
     * or an empty array (`[]`) depending on version and provider. Tolerate both
     * shapes via [LenientStringMapSerializer].
     */
    @Serializable(with = LenientStringMapSerializer::class)
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Reads a JSON value into `Map<String, String>`, accepting:
 *  - an object, with primitive values stringified;
 *  - an empty array (treated as an empty map — some Nextcloud builds
 *    serialise an empty PHP associative array as `[]`);
 *  - `null` or missing (empty map).
 */
internal object LenientStringMapSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("LenientStringMap")

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return delegate.deserialize(decoder)
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> element.mapValues { (_, v) ->
                when (v) {
                    is JsonPrimitive -> v.contentOrNull.orEmpty()
                    JsonNull -> ""
                    else -> v.toString()
                }
            }
            is JsonArray -> emptyMap()
            JsonNull -> emptyMap()
            else -> emptyMap()
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Map<String, String>,
    ) {
        delegate.serialize(encoder, value)
    }
}
