package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.PageAttention
import com.megamaced.nccollectives.domain.model.TagCount
import com.megamaced.nccollectives.util.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The decisions behind what Home shows, apart from drawing it. */
@RunWith(AndroidJUnit4::class)
class HomeFeedRulesTest {
    private val two = listOf(collective(1), collective(2))

    @Test
    fun theFilterNarrowsToALiveCollective() {
        assertEquals(2L, effectiveCollectiveFilter(2, two))
    }

    @Test
    fun allMeansNoFilter() {
        assertNull(effectiveCollectiveFilter(ALL_COLLECTIVES, two))
    }

    @Test
    fun aFilterOnACollectiveThatHasGoneMeansNoFilter() {
        assertNull(effectiveCollectiveFilter(99, two))
    }

    @Test
    fun theFilterIsOfferedOnlyWhenThereIsAChoice() {
        assertFalse(showsCollectiveFilter(emptyList()))
        assertFalse(showsCollectiveFilter(listOf(collective(1))))
        assertTrue(showsCollectiveFilter(two))
    }

    @Test
    fun aFeedWithNothingInItIsEmpty() {
        assertTrue(HomeFeed().isEmpty(recentFiltered = false))
    }

    @Test
    fun anyOneSectionMakesTheFeedNotEmpty() {
        val page = listOf(listItem(1))
        listOf(
            HomeFeed(attention = PageAttention(conflicts = page, queued = emptyList())),
            HomeFeed(jumpBackIn = page),
            HomeFeed(updatedSinceRead = page),
            HomeFeed(recentlyUpdated = page),
            HomeFeed(yourEdits = page),
            HomeFeed(tags = listOf(TagCount("t", 1))),
            HomeFeed(unseenAlerts = 1),
        ).forEach { assertFalse(it.toString(), it.isEmpty(recentFiltered = false)) }
    }

    @Test
    fun aFilterThatEmptiedTheRecentListDoesNotEmptyHome() {
        // The chips that would widen the filter again live in that section.
        assertFalse(HomeFeed().isEmpty(recentFiltered = true))
    }

    @Test
    fun attentionListsConflictsFirst_thenQueuedEditsInTheirOrder() {
        val rows = PageAttention(
            conflicts = listOf(listItem(3)),
            queued = listOf(listItem(1), listItem(2)),
        ).rows()

        assertEquals(listOf(3L, 1L, 2L), rows.map { it.page.id })
        assertEquals(listOf(true, false, false), rows.map { it.hasConflict })
    }

    @Test
    fun aPageWithAConflictAndAQueuedEditIsOneRowSayingBoth() {
        val rows = PageAttention(
            conflicts = listOf(listItem(1)),
            queued = listOf(listItem(1), listItem(2)),
        ).rows()

        assertEquals(listOf(1L, 2L), rows.map { it.page.id })
        assertTrue(rows[0].hasConflict && rows[0].hasQueuedEdit)
        assertTrue(rows[0].supporting.contains("waiting to sync"))
    }

    @Test
    fun sectionKeysRoundTrip_andAnUnknownKeyIsNoSection() {
        HomeSection.entries.forEach { assertEquals(it, HomeSection.fromKey(it.key)) }
        assertNull(HomeSection.fromKey("nope"))
        assertNull(HomeSection.fromKey(null))
    }

    @Test
    fun yourOwnEditsLeaveTheEditorOut() {
        val page = listItem(1, serverTimestamp = 1_000, lastUserDisplayName = "Alice")
        val now = 1_000_000L + 2 * 3_600_000L

        assertFalse(supportingLine(HomeSection.YourEdits, page, now)!!.english().contains("Alice"))
        assertTrue(supportingLine(HomeSection.RecentlyUpdated, page, now)!!.english().contains("Alice"))
    }

    @Test
    fun aCardSaysWhoEditedItAndWhen_shortly_andNotWhoAmongYourOwnEdits() {
        val now = 10_000_000_000L
        val page = listItem(1, serverTimestamp = now / 1000 - 7200, lastUserDisplayName = "Alice")

        assertEquals("Alice · 2 hours ago", cardLine(HomeSection.RecentlyUpdated, page, now)?.english())
        assertEquals("2 hours ago", cardLine(HomeSection.YourEdits, page, now)?.english())
        assertEquals("Alice", cardLine(HomeSection.UpdatedSinceRead, page.copy(serverTimestamp = 0), now)?.english())
        assertNull(cardLine(HomeSection.JumpBackIn, page.copy(serverTimestamp = 0, lastUserDisplayName = ""), now))
    }

    @Test
    fun thereAreAsManyCardColumnsAsFit_neverFewerThanTwo() {
        assertEquals("a narrow phone", 2, cardColumns(300.dp))
        assertEquals("a phone", 2, cardColumns(379.dp))
        assertEquals("an unfolded foldable", 3, cardColumns(568.dp))
        assertEquals("a tablet", 4, cardColumns(MIN_CARD_WIDTH * 4 + 8.dp * 3))
    }
}
