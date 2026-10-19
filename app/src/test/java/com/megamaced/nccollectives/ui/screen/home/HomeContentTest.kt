package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.TagCount
import com.megamaced.nccollectives.ui.screen.collective.NewPageDialog
import com.megamaced.nccollectives.ui.screen.collective.NewPageMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<Long>()
    private val seeAll = mutableListOf<HomeSection>()
    private val filters = mutableListOf<Long?>()
    private var alertsOpened = 0

    private fun show(
        feed: HomeFeed?,
        collectives: List<Collective> = listOf(collective(1), collective(2)),
        recentFilter: Long? = null,
    ) {
        rule.setContent {
            HomeContent(
                feed = feed,
                collectives = collectives,
                recentFilter = recentFilter,
                nowMillis = 0,
                listState = rememberLazyListState(),
                onOpenPage = { opened += it },
                onSeeAll = { seeAll += it },
                onRecentFilter = { filters += it },
                onBrowseTag = {},
                onOpenAlerts = { alertsOpened++ },
            )
        }
    }

    private fun pages(
        count: Int,
        from: Long = 1,
    ): List<PageListItem> = (from until from + count).map { listItem(it) }

    @Test
    fun nothingIsDrawnUntilTheCacheAnswers() {
        show(feed = null)

        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
    }

    @Test
    fun anEmptyFeedSaysSo() {
        show(feed = HomeFeed())

        rule.onNodeWithText("Nothing here yet").assertExists()
    }

    @Test
    fun onlySectionsWithSomethingInThemAreShown() {
        show(feed = HomeFeed(recentlyUpdated = pages(2), tags = listOf(TagCount("soup", 3))))

        rule.onNodeWithText(HomeSection.RecentlyUpdated.title).assertExists()
        rule.onNodeWithText("Browse by tag").assertExists()
        rule.onNodeWithText("soup · 3").assertExists()
        listOf(HomeSection.Attention, HomeSection.JumpBackIn, HomeSection.UpdatedSinceRead, HomeSection.YourEdits)
            .forEach { rule.onNodeWithText(it.title).assertDoesNotExist() }
        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
    }

    @Test
    fun unseenAlertsAreOfferedFirstInNeedsAttention_andLeadToTheAlertsTab() {
        show(feed = HomeFeed(unseenAlerts = 3))

        rule.onNodeWithText(HomeSection.Attention.title).assertExists()
        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
        rule.onNodeWithText("3 new alerts").performClick()
        assertEquals(1, alertsOpened)
    }

    @Test
    fun withNoUnseenAlertsThereIsNoAlertsRow() {
        show(feed = HomeFeed(updatedSinceRead = pages(1)))

        rule.onNodeWithText("new alert", substring = true).assertDoesNotExist()
        rule.onNodeWithText(HomeSection.Attention.title).assertDoesNotExist()
    }

    @Test
    fun aFullPreviewOffersNoSeeAll() {
        show(feed = HomeFeed(updatedSinceRead = pages(HOME_PREVIEW_CARDS)))

        rule.onNodeWithText("Page $HOME_PREVIEW_CARDS").assertExists()
        rule.onNodeWithText("See all").assertDoesNotExist()
    }

    @Test
    fun oneCardMoreThanThePreviewOffersSeeAll_andIsNotShown() {
        show(feed = HomeFeed(updatedSinceRead = pages(HOME_PREVIEW_CARDS + 1)))

        rule.onNodeWithText("Page $HOME_PREVIEW_CARDS").assertExists()
        rule.onNodeWithText("Page ${HOME_PREVIEW_CARDS + 1}").assertDoesNotExist()
        rule.onNodeWithText("See all").performClick()
        assertEquals(listOf(HomeSection.UpdatedSinceRead), seeAll)
    }

    @Test
    fun aCardSaysWhichCollectiveAndWhoEditedIt() {
        show(
            feed = HomeFeed(recentlyUpdated = listOf(listItem(5, collectiveId = 2, lastUserDisplayName = "Alice"))),
            // One collective, so no filter chips repeat its name.
            collectives = listOf(collective(2)),
        )

        rule.onNodeWithText("Collective 2").assertExists()
        rule.onNodeWithText("Alice", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp") // Tall enough that every section is drawn.
    fun theCardSectionsOfferSeeAll_onlyPastAFullGrid() {
        show(
            feed = HomeFeed(
                recentlyUpdated = pages(HOME_PREVIEW_CARDS),
                yourEdits = pages(HOME_PREVIEW_CARDS + 1, from = 100),
            ),
        )

        rule.onAllNodesWithText("See all").assertCountEquals(1)
        rule.onNodeWithText("See all").performClick()
        assertEquals("only Your recent edits has more than a grid", listOf(HomeSection.YourEdits), seeAll)
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp")
    fun aFullGridOfYourEditsOffersNoSeeAll() {
        show(
            feed = HomeFeed(
                recentlyUpdated = pages(HOME_PREVIEW_CARDS + 1),
                yourEdits = pages(HOME_PREVIEW_CARDS, from = 100),
            ),
        )

        rule.onAllNodesWithText("See all").assertCountEquals(1)
        rule.onNodeWithText("See all").performClick()
        assertEquals(listOf(HomeSection.RecentlyUpdated), seeAll)
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp")
    fun recentlyUpdatedDrawsNoMoreThanAGrid() {
        show(feed = HomeFeed(recentlyUpdated = pages(HOME_PREVIEW_CARDS + 1)))

        rule.onNodeWithText("Page $HOME_PREVIEW_CARDS").assertExists()
        rule.onNodeWithText("Page ${HOME_PREVIEW_CARDS + 1}").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h1600dp")
    fun everyCardSectionAgesWithTheScreensClock() {
        val edited = 1_000L
        var now by mutableStateOf(edited * 1_000L + 2 * 60_000L)
        val feed = HomeFeed(
            jumpBackIn = listOf(listItem(1, serverTimestamp = edited)),
            updatedSinceRead = listOf(listItem(2, serverTimestamp = edited)),
            recentlyUpdated = listOf(listItem(3, serverTimestamp = edited)),
            yourEdits = listOf(listItem(4, serverTimestamp = edited)),
        )
        rule.setContent {
            HomeContent(
                feed = feed,
                collectives = listOf(collective(1)),
                recentFilter = null,
                nowMillis = now,
                listState = rememberLazyListState(),
                onOpenPage = {},
                onSeeAll = {},
                onRecentFilter = {},
                onBrowseTag = {},
                onOpenAlerts = {},
            )
        }
        rule.onAllNodesWithText("2 minutes ago").assertCountEquals(4)

        rule.runOnIdle { now = edited * 1_000L + 3 * 3_600_000L }

        rule.onAllNodesWithText("3 hours ago").assertCountEquals(4)
    }

    @Test
    fun aCardOpensItsPage() {
        show(feed = HomeFeed(yourEdits = listOf(listItem(9, title = "Mine"))))

        rule.onNodeWithText("Mine").performClick()
        assertEquals(listOf(9L), opened)
    }

    @Test
    fun aFilterThatFindsNothingKeepsTheChipsToWidenItAgain() {
        show(feed = HomeFeed(), recentFilter = 2)

        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
        rule.onNodeWithText("Nothing recent in this collective.").assertExists()
        rule.onNodeWithText("All").performClick()
        assertEquals(listOf<Long?>(null), filters)
    }

    @Test
    fun withOneCollectiveThereAreNoChips() {
        show(feed = HomeFeed(recentlyUpdated = pages(1)), collectives = listOf(collective(1)))

        rule.onNodeWithText("All").assertDoesNotExist()
    }

    @Test
    fun aRowOpensItsPage() {
        show(feed = HomeFeed(recentlyUpdated = listOf(listItem(7, title = "Soup"))))

        rule.onNodeWithText("Soup").performClick()
        assertEquals(listOf(7L), opened)
    }

    @Test
    fun parentsArrivingAfterTheDialogOpensStillGetADefault() {
        var choices by mutableStateOf(emptyList<PageListItem>())
        val created = mutableListOf<Pair<Long, String>>()
        rule.setContent {
            NewPageDialog(
                mode = NewPageMode.PickParent,
                parentChoices = choices,
                onCreate = { parentId, title -> created += parentId to title },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Title").performTextInput("  Fresh  ")
        rule.onNodeWithText("Create").assertIsNotEnabled()

        choices = listOf(listItem(3, parentId = 10, title = "Child"), listItem(10, parentId = 0, title = "Landing"))
        rule.waitForIdle()

        rule.onNodeWithText("Landing").assertExists()
        rule.onNodeWithText("Create").assertIsEnabled().performClick()
        assertEquals(listOf(10L to "Fresh"), created)
    }

    @Test
    fun aFixedParentStaysFixed_whateverTheChoices() {
        val created = mutableListOf<Long>()
        rule.setContent {
            NewPageDialog(
                mode = NewPageMode.FixedParent(parentId = 3),
                parentChoices = listOf(listItem(10, parentId = 0, title = "Landing")),
                onCreate = { parentId, _ -> created += parentId },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Where should this page go?").assertDoesNotExist()
        rule.onNodeWithText("Title").performTextInput("Sub")
        rule.onNodeWithText("Create").performClick()
        assertEquals(listOf(3L), created)
    }

    @Test
    fun parentsStillLoadingForANewCollectiveAreNotOfferedForIt() {
        // The user has just moved from collective 1 to 2; 1's pages are all
        // the dialog has so far.
        var parents by mutableStateOf<NewPageParents?>(
            NewPageParents(1, listOf(listItem(10, collectiveId = 1, parentId = 0, title = "One's landing"))),
        )
        val created = mutableListOf<Triple<Long, Long, String>>()
        rule.setContent {
            HomeNewPageDialog(
                collectives = listOf(collective(1), collective(2)),
                collectiveId = 2,
                parents = parents,
                onSelectCollective = {},
                onCreate = { collectiveId, parentId, title -> created += Triple(collectiveId, parentId, title) },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Title").performTextInput("Fresh")

        rule.onNodeWithText("One's landing").assertDoesNotExist()
        rule.onNodeWithText("Create").assertIsNotEnabled()

        parents = NewPageParents(2, listOf(listItem(20, collectiveId = 2, parentId = 0, title = "Two's landing")))
        rule.waitForIdle()
        rule.onNodeWithText("Create").assertIsEnabled().performClick()
        assertEquals(listOf(Triple(2L, 20L, "Fresh")), created)
    }

    @Test
    fun aCollectiveTheUserCanNoLongerWriteToCannotTakeTheNewPage() {
        rule.setContent {
            HomeNewPageDialog(
                // Collective 2 was chosen, then lost its write access.
                collectives = listOf(collective(1)),
                collectiveId = 2,
                parents = NewPageParents(2, listOf(listItem(20, collectiveId = 2, parentId = 0, title = "Two's landing"))),
                onSelectCollective = {},
                onCreate = { _, _, _ -> },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Title").performTextInput("Fresh")

        rule.onNodeWithText("Pick a collective…").assertExists()
        rule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun aPickedParentThatGoesAwayIsNotQuietlyReplaced() {
        val landing = listItem(10, parentId = 0, title = "Landing")
        var choices by mutableStateOf(listOf(landing, listItem(20, parentId = 10, title = "Chosen")))
        rule.setContent {
            NewPageDialog(mode = NewPageMode.PickParent, parentChoices = choices, onCreate = { _, _ -> }, onDismiss = {})
        }
        rule.onNodeWithText("Title").performTextInput("Child")
        rule.onNodeWithText("Landing").performClick()
        rule.onNodeWithText("Chosen").performClick()
        rule.onNodeWithText("Create").assertIsEnabled()

        choices = listOf(landing)
        rule.waitForIdle()

        rule.onNodeWithText("Pick a parent…").assertExists()
        rule.onNodeWithText("Create").assertIsNotEnabled()
    }
}
