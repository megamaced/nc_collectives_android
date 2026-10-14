package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One entry from the Notifications app's `GET /api/v2/notifications`.
 *
 * [subjectRichParameters] is kept as raw JSON because PHP serialises an
 * empty associative array as `[]`, so a notification without parameters
 * would fail a typed map; [richParameters] reads it leniently.
 */
@Serializable
data class NotificationDto(
    @SerialName("notification_id") val notificationId: Long,
    val app: String,
    /** ISO 8601 with offset, e.g. `2026-10-14T19:00:00+00:00`. */
    val datetime: String = "",
    @SerialName("object_type") val objectType: String = "",
    @SerialName("object_id") val objectId: String = "",
    /** The server's rendering of the rich subject, in the user's language. */
    val subject: String = "",
    val subjectRichParameters: JsonElement = JsonNull,
) {
    /**
     * The rich subject's parameters by placeholder name; empty when there
     * are none. A parameter that isn't an object, or whose `link` is there
     * but isn't a non-empty string, is malformed and left out: a missing
     * link says something (a deleted page has none), a broken one doesn't.
     */
    fun richParameters(): Map<String, RichObject> {
        val params = subjectRichParameters as? JsonObject ?: return emptyMap()
        return params
            .mapNotNull { (key, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val link = obj["link"]?.let { raw ->
                    (raw as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return@mapNotNull null
                }
                key to RichObject(
                    type = obj.string("type").orEmpty(),
                    id = obj.string("id").orEmpty(),
                    name = obj.string("name").orEmpty(),
                    link = link,
                )
            }.toMap()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** A rich-subject parameter: who, which page, which collective. */
data class RichObject(
    val type: String,
    val id: String,
    val name: String,
    /** Null when the parameter has no link. */
    val link: String?,
)
