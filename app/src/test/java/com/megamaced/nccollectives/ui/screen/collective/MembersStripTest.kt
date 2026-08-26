package com.megamaced.nccollectives.ui.screen.collective

import com.megamaced.nccollectives.ui.components.memberAvatarUrl
import com.megamaced.nccollectives.ui.components.monogramOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three pure functions behind `MembersStrip`. All three are pure for the
 * same reason: they encode decisions that go wrong in ways a screenshot
 * won't show — a strip that silently draws nothing, a monogram that renders
 * as a replacement box, or an avatar URL that 404s for every user whose id
 * has an `@` in it.
 *
 *  - [visibleAvatarCount] is the port of upstream's
 *    `min(members.length, floor(containerWidth / slot) - 1, 15)`. The cases
 *    below are its three edges: an unmeasured container, a container too
 *    narrow for one slot once the trailing action is paid for, and a large
 *    Team against the 15 cap.
 *  - [monogramOf] runs on every avatar that fails to load, which on a real
 *    instance means every federated and every deleted member.
 *  - [memberAvatarUrl] is the S-27 boundary: a server-supplied `userId`
 *    spliced into a request path that `AuthInterceptor` will attach
 *    Basic-auth to.
 *
 * Astral characters are written as escapes rather than literals so the
 * surrogate pairs these cases exist to test are unmistakable in the source.
 */
class MembersStripTest {
    // --- visibleAvatarCount: how many avatars fit ---

    @Test
    fun `an unmeasured container shows nothing`() {
        // First composition, before BoxWithConstraints has a width. Drawing
        // avatars against a width of 0 would size them off-screen.
        assertEquals(0, visibleAvatarCount(containerWidthDp = 0, memberCount = 10))
    }

    @Test
    fun `a negative width shows nothing`() {
        assertEquals(0, visibleAvatarCount(containerWidthDp = -100, memberCount = 10))
    }

    @Test
    fun `a container too narrow for one slot shows nothing`() {
        // 95dp less the 48dp reserved for the trailing action leaves 47dp
        // usable, one dp short of a 48dp slot.
        assertEquals(0, visibleAvatarCount(containerWidthDp = 95, memberCount = 10))
    }

    @Test
    fun `one slot exactly fits one avatar`() {
        assertEquals(1, visibleAvatarCount(containerWidthDp = 96, memberCount = 10))
    }

    @Test
    fun `the trailing action costs exactly one slot`() {
        // The same width with nothing reserved fits two. This is the
        // named-width replacement for upstream's `- 1`, so it is worth
        // pinning that it costs what it claims to.
        assertEquals(
            2,
            visibleAvatarCount(containerWidthDp = 96, memberCount = 10, reservedWidthDp = 0),
        )
    }

    @Test
    fun `a phone-width strip fits a handful`() {
        // A 360dp screen less 32dp of horizontal padding leaves 328dp of
        // strip; less the 48dp action, 280dp — five 48dp slots and 40dp
        // spare.
        assertEquals(5, visibleAvatarCount(containerWidthDp = 328, memberCount = 10))
    }

    @Test
    fun `a small team is not padded out to what fits`() {
        assertEquals(3, visibleAvatarCount(containerWidthDp = 4000, memberCount = 3))
    }

    @Test
    fun `a large team is capped at fifteen`() {
        // A width that fits 82 slots and a Team of 200: upstream's hard cap
        // is what stops the strip, not the width and not the Team size.
        assertEquals(15, visibleAvatarCount(containerWidthDp = 4000, memberCount = 200))
        assertEquals(
            MAX_STRIP_AVATARS,
            visibleAvatarCount(containerWidthDp = 4000, memberCount = 200),
        )
    }

    @Test
    fun `no members means no avatars however wide the strip`() {
        assertEquals(0, visibleAvatarCount(containerWidthDp = 4000, memberCount = 0))
    }

    @Test
    fun `a degenerate slot width does not divide by zero`() {
        assertEquals(
            0,
            visibleAvatarCount(containerWidthDp = 1000, memberCount = 5, slotWidthDp = 0),
        )
    }

    // --- monogramOf: the avatar fallback ---

    @Test
    fun `a two-word display name gives two initials`() {
        assertEquals("DM", monogramOf("David Mace"))
    }

    @Test
    fun `a one-word display name gives one initial`() {
        assertEquals("A", monogramOf("admin"))
    }

    @Test
    fun `only the first and last word are used`() {
        assertEquals("JS", monogramOf("Jean Luc de Something"))
    }

    @Test
    fun `an email-shaped id drops its domain`() {
        // The whole reason the domain is stripped: initials of "David" and
        // "macemail" would be initials of something that is not a name.
        assertEquals("D", monogramOf("david@macemail.co.uk"))
    }

    @Test
    fun `a dotted local part still reads as first and last name`() {
        assertEquals("DM", monogramOf("david.mace@macemail.co.uk"))
    }

    @Test
    fun `underscores and hyphens separate words`() {
        assertEquals("SA", monogramOf("service_account"))
        assertEquals("AM", monogramOf("anne-marie"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("DM", monogramOf("  David Mace  "))
    }

    @Test
    fun `a blank label falls back rather than drawing nothing`() {
        assertEquals("?", monogramOf(""))
        assertEquals("?", monogramOf("   "))
    }

    @Test
    fun `a label with no letter or digit falls back`() {
        // A bare "@" is what a mail membership with no display name and a
        // truncated address can arrive as; "@" is not a monogram.
        assertEquals("?", monogramOf("@"))
        assertEquals("?", monogramOf("!!!"))
    }

    @Test
    fun `an emoji-only label falls back instead of drawing half a glyph`() {
        // U+1F427 PENGUIN is a surrogate pair and is not a letter. `take(1)`
        // here would hand Compose a lone high surrogate, which draws as the
        // replacement box.
        assertEquals("?", monogramOf("\uD83D\uDC27"))
    }

    @Test
    fun `an astral letter survives whole`() {
        // U+10400 DESERET CAPITAL LETTER LONG I is a letter *and* a
        // surrogate pair, so it is the case that separates walking code
        // points from walking chars: a char-at-a-time scan sees two
        // surrogates, neither of which is a letter, and gives up.
        val monogram = monogramOf("\uD801\uDC00eseret")
        assertEquals("\uD801\uDC00", monogram)
        assertEquals(2, monogram.length)
    }

    @Test
    fun `initials are upper-cased`() {
        assertEquals("BS", monogramOf("björn straße"))
    }

    // --- memberAvatarUrl: the S-27 path boundary ---

    @Test
    fun `an email-shaped id keeps its at sign and gains the requested size`() {
        // `@` is a legal path character and the avatar route accepts it, so
        // it stays literal rather than becoming %40. The host is pinned
        // deliberately: it is what makes `HostInterceptor` classify this as
        // `RequestOrigin.AppBaseUrl` and hand it credentials.
        assertEquals(
            "https://placeholder.invalid/index.php/avatar/david@macemail.co.uk/128",
            memberAvatarUrl("david@macemail.co.uk", sizePx = 128),
        )
    }

    @Test
    fun `characters that would change the request are percent-encoded`() {
        assertEquals(
            "https://placeholder.invalid/index.php/avatar/a%20b/64",
            memberAvatarUrl("a b", sizePx = 64),
        )
        assertEquals(
            "https://placeholder.invalid/index.php/avatar/who%3Fme/64",
            memberAvatarUrl("who?me", sizePx = 64),
        )
        assertEquals(
            "https://placeholder.invalid/index.php/avatar/%C3%BCser/64",
            memberAvatarUrl("üser", sizePx = 64),
        )
    }

    @Test
    fun `an id carrying path structure is refused outright`() {
        // Not encoded and sent anyway: an id with a separator or a
        // traversal in it means the string carried structure we don't
        // trust, so no request is made and the caller draws a monogram.
        assertNull(memberAvatarUrl("a/b"))
        assertNull(memberAvatarUrl("a\\b"))
        assertNull(memberAvatarUrl(".."))
        assertNull(memberAvatarUrl("."))
    }

    @Test
    fun `a blank id is refused`() {
        assertNull(memberAvatarUrl(""))
        assertNull(memberAvatarUrl("   "))
    }

    @Test
    fun `padding around an id is trimmed rather than encoded`() {
        assertEquals(
            "https://placeholder.invalid/index.php/avatar/bob/128",
            memberAvatarUrl(" bob "),
        )
    }

    @Test
    fun `the default size is the one the strip draws at`() {
        assertEquals(
            "https://placeholder.invalid/index.php/avatar/admin/128",
            memberAvatarUrl("admin"),
        )
    }
}
