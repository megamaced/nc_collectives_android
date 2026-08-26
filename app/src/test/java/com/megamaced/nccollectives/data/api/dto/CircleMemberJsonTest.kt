package com.megamaced.nccollectives.data.api.dto

import com.megamaced.nccollectives.data.api.Envelope
import com.megamaced.nccollectives.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Circles members payload is the awkward one in this app, and it is
 * awkward in a way no amount of care at the call site can fix — so it is
 * pinned here instead.
 *
 * `notes` is type-polymorphic *within a single response*: PHP's
 * `json_encode` of an empty associative array emits `{}` or `[]` depending
 * on how the array was built, and Circles 34.0.0 does both — an object on
 * the member itself, an array under `basedOn.owner.notes`. Declaring the
 * field on [CircleMemberDto] as either shape throws on the members that
 * carry the other, and only on some members, which reads like bad data
 * rather than a bad schema.
 *
 * These tests decode through `NetworkModule.provideJson()` rather than a
 * local [kotlinx.serialization.json.Json] so they assert the behaviour of
 * the configuration the app actually ships. A future edit that drops
 * `ignoreUnknownKeys` fails here.
 */
class CircleMemberJsonTest {
    private val json = NetworkModule.provideJson()

    /**
     * Verbatim from `GET /ocs/v2.php/apps/circles/circles/{id}/members` on
     * Circles 34.0.0 / Nextcloud 34.0.3, with two members added: one whose
     * `basedOn.owner.notes` is the empty *array* shape seen live, and one
     * whose own `notes` is an array, so neither position is assumed stable.
     */
    private val realPayload =
        """
        {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":[
         {"id":"SRl4iLM5Wzf9gWIL5QBUEtjmHFXGbZc","circleId":"KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo",
          "singleId":"9PUc43WwDHVGBPnfn7wSdTWYrScodcO","userId":"david@macemail.co.uk","userType":1,
          "instance":"macecloud.co.uk","local":true,"level":9,"status":"Member","displayName":"David Mace",
          "displayUpdate":1787740866,"notes":{},"contactId":"","contactMeta":"","joined":1778415791,
          "invitedBy":{},"basedOn":{}},
         {"id":"Xb2mQ4nRt7yPl0aScVdFgHjKmNoPqRs","circleId":"KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo",
          "singleId":"Tt8uVvWwXxYyZz1122334455667788","userId":"rowan","userType":1,
          "instance":"macecloud.co.uk","local":true,"level":4,"status":"Member","displayName":"Rowan Ash",
          "displayUpdate":1787740999,"notes":{},"contactId":"","contactMeta":"","joined":1778415888,
          "invitedBy":{},"basedOn":{"singleId":"KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo","name":"Field Guide",
            "owner":{"id":"SRl4iLM5Wzf9gWIL5QBUEtjmHFXGbZc","userId":"david@macemail.co.uk",
              "userType":1,"level":9,"displayName":"David Mace","notes":[]}}},
         {"id":"Zz9yYxXwWvVuUtTsSrRqQpPoOnNmMl","circleId":"KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo",
          "singleId":"Aa1bB2cC3dD4eE5fF6gG7hH8iI9jJ0","userId":"crew","userType":2,
          "instance":"macecloud.co.uk","local":true,"level":1,"status":"Member","displayName":"Field crew",
          "displayUpdate":1787741100,"notes":[],"contactId":"","contactMeta":"","joined":1778415900,
          "invitedBy":[],"basedOn":[]}]}}
        """.trimIndent()

    @Test
    fun `notes as an object and as an empty array both deserialise`() {
        val members = json
            .decodeFromString<Envelope<List<CircleMemberDto>>>(realPayload)
            .ocs
            .data

        assertEquals(3, members.size)
        assertEquals(
            listOf("David Mace", "Rowan Ash", "Field crew"),
            members.map { it.displayName },
        )
    }

    @Test
    fun `the lean DTO keeps the six fields it declares`() {
        val member = json
            .decodeFromString<Envelope<List<CircleMemberDto>>>(realPayload)
            .ocs
            .data
            .first()

        assertEquals("SRl4iLM5Wzf9gWIL5QBUEtjmHFXGbZc", member.id)
        assertEquals("9PUc43WwDHVGBPnfn7wSdTWYrScodcO", member.singleId)
        assertEquals("david@macemail.co.uk", member.userId)
        assertEquals(1, member.userType)
        assertEquals(9, member.level)
        assertEquals("David Mace", member.displayName)
    }

    /**
     * `data` is a bare array here, unlike every Collectives endpoint, which
     * wraps its list in a single-key object. That is what lets the shared
     * [Envelope] generic carry `List<CircleMemberDto>` directly, so it is
     * worth pinning: a server that started wrapping it would otherwise fail
     * at runtime with a deserialisation error and no explanation.
     */
    @Test
    fun `meta is parsed alongside the bare data array`() {
        val envelope = json.decodeFromString<Envelope<List<CircleMemberDto>>>(realPayload)

        assertEquals("ok", envelope.ocs.meta.status)
        assertEquals(200, envelope.ocs.meta.statuscode)
    }

    /** A member with only the required id still parses; the rest defaults. */
    @Test
    fun `sparse member falls back to defaults rather than failing the list`() {
        val sparse =
            """
            {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[{"id":"only-an-id"}]}}
            """.trimIndent()

        val member = json
            .decodeFromString<Envelope<List<CircleMemberDto>>>(sparse)
            .ocs.data
            .single()

        assertEquals("only-an-id", member.id)
        assertEquals("", member.singleId)
        assertEquals("", member.userId)
        assertEquals(0, member.level)
    }
}
