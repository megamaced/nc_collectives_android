package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.api.Envelope
import com.megamaced.nccollectives.data.api.dto.CollectiveDto
import com.megamaced.nccollectives.data.api.dto.CollectivesEnvelopeData
import com.megamaced.nccollectives.di.NetworkModule
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-83: `circleId`, `level` and `userShowMembers` have to survive all three
 * hops — wire, Room row, domain model — or the members feature has nothing
 * to address the Circles API with.
 *
 * The first test decodes the wire names through the shipped
 * `NetworkModule.provideJson()`, because the failure mode of a mistyped
 * `@SerialName` is not an error: the field silently takes its default and
 * every collective looks like it came from an old server.
 */
class CollectiveMapperTest {
    private val json = NetworkModule.provideJson()

    @Test
    fun `the team fields parse from the wire names Collectives uses`() {
        val payload =
            """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":{"collectives":[
             {"id":3,"circleId":"KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo","name":"Field Guide",
              "slug":"field-guide-3","emoji":"🌿","level":9,"editPermissionLevel":1,
              "sharePermissionLevel":1,"canEdit":true,"canShare":true,"isPageShare":false,
              "userShowMembers":true,"userFavoritePages":[12,34]}]}}}
            """.trimIndent()

        val dto = json
            .decodeFromString<Envelope<CollectivesEnvelopeData>>(payload)
            .ocs
            .data
            .collectives
            .single()

        assertEquals("KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo", dto.circleId)
        assertEquals(9, dto.level)
        assertTrue(dto.userShowMembers)
    }

    @Test
    fun `an older server that omits them still parses, with honest defaults`() {
        val payload =
            """
            {"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"collectives":[
             {"id":3,"name":"Field Guide","canEdit":true}]}}}
            """.trimIndent()

        val dto = json
            .decodeFromString<Envelope<CollectivesEnvelopeData>>(payload)
            .ocs
            .data
            .collectives
            .single()

        // No id to address membership with, rather than a plausible-looking one.
        assertNull(dto.circleId)
        // 0 is not a level Circles issues, so it can only mean "unknown".
        assertEquals(0, dto.level)
        // An absent display preference is not an opt-out.
        assertTrue(dto.userShowMembers)
    }

    @Test
    fun `entity and domain both carry the team fields`() {
        val dto = CollectiveDto(
            id = 3,
            name = "Field Guide",
            circleId = "KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo",
            level = 8,
            userShowMembers = false,
            canEdit = true,
            canShare = true,
        )

        val entity = dto.toEntity(now = 1_700_000_000_000L)
        assertEquals("KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo", entity.circleId)
        assertEquals(8, entity.level)
        assertFalse(entity.userShowMembers)

        val domain = entity.toDomain()
        assertEquals("KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo", domain.circleId)
        assertEquals(8, domain.level)
        assertEquals(CollectiveMemberLevel.Admin, domain.userLevel)
        assertFalse(domain.userShowMembers)
    }

    /**
     * A blank circleId can only produce `/circles//members`, which is a
     * different route with a misleading answer, so it is normalised to null
     * at the boundary rather than checked for at every call site.
     */
    @Test
    fun `a blank circleId is normalised to null`() {
        val entity = CollectiveDto(id = 3, name = "Field Guide", circleId = "   ").toEntity(now = 0L)

        assertNull(entity.circleId)
        assertNull(entity.toDomain().circleId)
    }

    @Test
    fun `an unknown level surfaces as Unknown rather than as a role`() {
        val domain = CollectiveDto(id = 3, name = "Field Guide").toEntity(now = 0L).toDomain()

        assertEquals(0, domain.level)
        assertEquals(CollectiveMemberLevel.Unknown, domain.userLevel)
    }
}
