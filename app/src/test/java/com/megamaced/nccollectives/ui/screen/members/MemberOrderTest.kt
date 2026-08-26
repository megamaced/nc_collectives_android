package com.megamaced.nccollectives.ui.screen.members

import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import com.megamaced.nccollectives.domain.model.CollectiveMemberType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MEMBER_ORDER] — the order the members list is presented in.
 *
 * Worth pinning for two reasons that have nothing to do with tidiness. The
 * ordering is the screen's only answer to "who runs this collective", so a
 * level comparison that slips the wrong way round is a factual error about
 * permissions rather than a cosmetic one. And the comparator's name key is
 * `label`, not `displayName`, precisely because blank display names are
 * normal here — a regression to the raw field would silently herd every
 * mail and contact member into one lump at the top.
 */
class MemberOrderTest {
    @Test
    fun `levels sort most privileged first`() {
        val sorted = listOf(
            member("a", level = CollectiveMemberLevel.Member),
            member("b", level = CollectiveMemberLevel.Owner),
            member("c", level = CollectiveMemberLevel.Moderator),
            member("d", level = CollectiveMemberLevel.Admin),
        ).sortedWith(MEMBER_ORDER)

        assertEquals(listOf("b", "d", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `an unrecognised level sorts below a plain member`() {
        // The whole point of `Unknown` being raw 0: a level the server
        // didn't send must never surface above real members, where it would
        // read as authority the app has no evidence for.
        val sorted = listOf(
            member("unknown", level = CollectiveMemberLevel.Unknown, displayName = "Aaron"),
            member("member", level = CollectiveMemberLevel.Member, displayName = "Zoe"),
        ).sortedWith(MEMBER_ORDER)

        assertEquals(listOf("member", "unknown"), sorted.map { it.id })
    }

    @Test
    fun `members of one level sort by name, ignoring case`() {
        val sorted = listOf(
            member("1", displayName = "carol"),
            member("2", displayName = "Alice"),
            member("3", displayName = "bob"),
        ).sortedWith(MEMBER_ORDER)

        // Not `compareBy { displayName }`: that puts every capitalised name
        // ahead of every lower-case one, so "Zoe" precedes "alice".
        assertEquals(listOf("Alice", "bob", "carol"), sorted.map { it.displayName })
    }

    @Test
    fun `a blank display name sorts on the login name instead`() {
        val sorted = listOf(
            member("bob", displayName = "Bob", userId = "bob@macemail.co.uk"),
            // A mail membership: no display name is the normal case, not a
            // data fault.
            member(
                "mail",
                displayName = "",
                userId = "alice@macemail.co.uk",
                type = CollectiveMemberType.Mail,
            ),
        ).sortedWith(MEMBER_ORDER)

        assertEquals(listOf("mail", "bob"), sorted.map { it.id })
    }

    @Test
    fun `a member with neither name nor login sorts on its membership id`() {
        val sorted = listOf(
            member("zulu", displayName = "", userId = ""),
            member("alpha", displayName = "", userId = ""),
        ).sortedWith(MEMBER_ORDER)

        // `label`'s last fallback. The rows are useless to read either way,
        // but they must not reshuffle between loads.
        assertEquals(listOf("alpha", "zulu"), sorted.map { it.id })
    }

    @Test
    fun `non-user memberships are ordered by level and name like everything else`() {
        // Kind is signalled in the row, not in the ordering: a moderator
        // group holds a moderator's powers, so demoting it below the people
        // would misrepresent who can do what.
        val sorted = listOf(
            member("user", displayName = "Zoe", type = CollectiveMemberType.User),
            member(
                "group",
                displayName = "Editors",
                level = CollectiveMemberLevel.Moderator,
                type = CollectiveMemberType.Group,
            ),
            member(
                "circle",
                displayName = "Ops",
                level = CollectiveMemberLevel.Owner,
                type = CollectiveMemberType.Circle,
            ),
        ).sortedWith(MEMBER_ORDER)

        assertEquals(listOf("circle", "group", "user"), sorted.map { it.id })
    }

    @Test
    fun `same level and same name fall back to the membership id`() {
        // Display names are not unique on a Nextcloud, so this is reachable.
        // Without a total order the list reshuffles between loads, which
        // reads as a bug in the screen rather than as a tie.
        val sorted = listOf(
            member("m-2", displayName = "David Mace"),
            member("m-1", displayName = "David Mace"),
        ).sortedWith(MEMBER_ORDER)

        assertEquals(listOf("m-1", "m-2"), sorted.map { it.id })
    }

    private fun member(
        id: String,
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
