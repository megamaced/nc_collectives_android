package com.megamaced.nccollectives.ui.screen.members

import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import com.megamaced.nccollectives.domain.model.CollectiveMemberType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings a members row puts on screen.
 *
 * These are assertions about honesty rather than about wording. Two of them
 * would be wrong in a way no compiler catches: an `Unknown` level rendered
 * as "Member" states a permission the server never claimed, and a non-user
 * membership rendered with nothing to mark it invites the reader to treat a
 * group or a bare email address as a colleague they can message. The `when`
 * expressions are exhaustive, so a new enum entry fails to compile rather
 * than silently falling through to a default — these tests cover the values
 * the entries map *to*.
 */
class MemberLabelsTest {
    @Test
    fun `each real level has its own label`() {
        assertEquals("Owner", memberRoleLabel(CollectiveMemberLevel.Owner))
        assertEquals("Admin", memberRoleLabel(CollectiveMemberLevel.Admin))
        assertEquals("Moderator", memberRoleLabel(CollectiveMemberLevel.Moderator))
        assertEquals("Member", memberRoleLabel(CollectiveMemberLevel.Member))
    }

    @Test
    fun `an unknown level does not borrow the name of a real role`() {
        val label = memberRoleLabel(CollectiveMemberLevel.Unknown)

        assertEquals("Role not reported", label)
        // The failure mode being guarded: level 0 means the payload carried
        // no level, which is not evidence of membership at the lowest rung.
        assertFalse(label in listOf("Owner", "Admin", "Moderator", "Member"))
    }

    @Test
    fun `a user account carries no kind label`() {
        // Null, not "User": prefixing every ordinary row would bury the one
        // signal the label exists to carry.
        assertNull(memberKindLabel(CollectiveMemberType.User))
    }

    @Test
    fun `every non-user kind is labelled`() {
        val labelled = CollectiveMemberType.entries
            .filter { it != CollectiveMemberType.User }
            .associateWith { memberKindLabel(it) }

        labelled.forEach { (type, label) ->
            assertNotNull("$type must be marked as not a person", label)
        }
    }

    @Test
    fun `single and unknown share one label, because the wire cannot tell them apart`() {
        // `CircleMemberDto.userType` defaults to 0, which is also the real
        // value for a single — so naming either specifically would be a
        // guess dressed as a fact.
        assertEquals(
            memberKindLabel(CollectiveMemberType.Single),
            memberKindLabel(CollectiveMemberType.Unknown),
        )
    }

    @Test
    fun `a user subtitle is just the role`() {
        val subtitle = memberSubtitle(
            member(level = CollectiveMemberLevel.Admin, type = CollectiveMemberType.User),
        )

        assertEquals("Admin", subtitle)
    }

    @Test
    fun `a non-user subtitle names the kind before the role`() {
        val subtitle = memberSubtitle(
            member(level = CollectiveMemberLevel.Moderator, type = CollectiveMemberType.Group),
        )

        assertEquals("Group · Moderator", subtitle)
    }

    @Test
    fun `a non-user with an unknown level says so twice over`() {
        val subtitle = memberSubtitle(
            member(level = CollectiveMemberLevel.Unknown, type = CollectiveMemberType.Mail),
        )

        assertEquals("Email address · Role not reported", subtitle)
    }

    private fun member(
        id: String = "m-1",
        level: CollectiveMemberLevel = CollectiveMemberLevel.Member,
        displayName: String = id,
        userId: String = "$id@macemail.co.uk",
        type: CollectiveMemberType = CollectiveMemberType.User,
    ) = CollectiveMember(
        id = id,
        singleId = "single-$id",
        userId = userId,
        displayName = displayName,
        level = level,
        type = type,
    )
}
