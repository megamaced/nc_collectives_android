package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.api.dto.CircleMemberDto
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import com.megamaced.nccollectives.domain.model.CollectiveMemberType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CircleMemberDto] to `CollectiveMember`. The mapper is where the Circles
 * wire numbers stop being numbers, so this is what stops two screens from
 * disagreeing about what level 8 means.
 */
class CollectiveMemberMapperTest {
    private fun dto(
        id: String = "SRl4iLM5Wzf9gWIL5QBUEtjmHFXGbZc",
        singleId: String = "9PUc43WwDHVGBPnfn7wSdTWYrScodcO",
        userId: String = "david@macemail.co.uk",
        userType: Int = 1,
        level: Int = 9,
        displayName: String = "David Mace",
    ) = CircleMemberDto(
        id = id,
        singleId = singleId,
        userId = userId,
        userType = userType,
        level = level,
        displayName = displayName,
    )

    @Test
    fun `every field crosses the boundary`() {
        val member = dto().toDomain()

        assertEquals("SRl4iLM5Wzf9gWIL5QBUEtjmHFXGbZc", member.id)
        assertEquals("9PUc43WwDHVGBPnfn7wSdTWYrScodcO", member.singleId)
        assertEquals("david@macemail.co.uk", member.userId)
        assertEquals("David Mace", member.displayName)
        assertEquals(CollectiveMemberLevel.Owner, member.level)
        assertEquals(CollectiveMemberType.User, member.type)
        assertEquals("David Mace", member.label)
    }

    @Test
    fun `levels map to the numbers the live capability payload uses`() {
        assertEquals(CollectiveMemberLevel.Member, dto(level = 1).toDomain().level)
        assertEquals(CollectiveMemberLevel.Moderator, dto(level = 4).toDomain().level)
        assertEquals(CollectiveMemberLevel.Admin, dto(level = 8).toDomain().level)
        assertEquals(CollectiveMemberLevel.Owner, dto(level = 9).toDomain().level)
    }

    @Test
    fun `member types map across the whole documented range`() {
        assertEquals(CollectiveMemberType.Single, dto(userType = 0).toDomain().type)
        assertEquals(CollectiveMemberType.User, dto(userType = 1).toDomain().type)
        assertEquals(CollectiveMemberType.Group, dto(userType = 2).toDomain().type)
        assertEquals(CollectiveMemberType.Mail, dto(userType = 4).toDomain().type)
        assertEquals(CollectiveMemberType.Contact, dto(userType = 8).toDomain().type)
        assertEquals(CollectiveMemberType.Circle, dto(userType = 16).toDomain().type)
        assertEquals(CollectiveMemberType.App, dto(userType = 10000).toDomain().type)
    }

    /**
     * A level or type this app doesn't know about must still produce a
     * member — the row is real, only its label is uncertain.
     */
    @Test
    fun `unrecognised level and type degrade to Unknown rather than dropping the member`() {
        val member = dto(level = 5, userType = 99).toDomain()

        assertEquals(CollectiveMemberLevel.Unknown, member.level)
        assertEquals(CollectiveMemberType.Unknown, member.type)
        assertEquals("David Mace", member.label)
    }

    /**
     * Unknown sorting below Member is what makes `level >= Admin` safe to
     * write: an unrecognised level can never read as more privileged than a
     * plain member.
     */
    @Test
    fun `level ordering is privilege ordering with Unknown at the bottom`() {
        assertEquals(
            listOf(
                CollectiveMemberLevel.Unknown,
                CollectiveMemberLevel.Member,
                CollectiveMemberLevel.Moderator,
                CollectiveMemberLevel.Admin,
                CollectiveMemberLevel.Owner,
            ),
            CollectiveMemberLevel.entries.sortedBy { it.raw },
        )
        assertTrue(CollectiveMemberLevel.Unknown < CollectiveMemberLevel.Member)
        assertTrue(CollectiveMemberLevel.Owner >= CollectiveMemberLevel.Admin)
    }

    /** Mail and contact memberships routinely arrive with no display name. */
    @Test
    fun `label falls back to userId when the server sends no display name`() {
        val member = dto(userType = 4, displayName = "  ", userId = "rowan@example.org").toDomain()

        assertEquals("", member.displayName)
        assertEquals("rowan@example.org", member.label)
    }

    @Test
    fun `label falls back to the membership id when there is nothing else`() {
        assertEquals("bare-id", dto(id = "bare-id", displayName = "", userId = "").toDomain().label)
    }

    /**
     * S-18: display strings from the server are sanitised before they reach
     * a list row — a newline in a display name is either a server bug or
     * someone probing the client, and the tag-CSV separator (U+001F) in a
     * userId would corrupt an unrelated column if it ever reached one.
     */
    @Test
    fun `display strings are sanitised at the trust boundary`() {
        val member = dto(displayName = "  David\nMace ", userId = "rowan\u001Fash").toDomain()

        assertEquals("DavidMace", member.displayName)
        assertEquals("rowanash", member.userId)
    }
}
