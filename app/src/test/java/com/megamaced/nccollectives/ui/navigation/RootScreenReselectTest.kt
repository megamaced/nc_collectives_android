package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.domain.model.PageAttention
import com.megamaced.nccollectives.domain.model.TagCount
import com.megamaced.nccollectives.domain.repository.AlertRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.screen.alerts.AlertsScreen
import com.megamaced.nccollectives.ui.screen.alerts.AlertsViewModel
import com.megamaced.nccollectives.ui.screen.alerts.alert
import com.megamaced.nccollectives.ui.screen.collective.CollectiveListScreen
import com.megamaced.nccollectives.ui.screen.collective.CollectiveListViewModel
import com.megamaced.nccollectives.ui.screen.favorites.FavoritesScreen
import com.megamaced.nccollectives.ui.screen.favorites.FavoritesViewModel
import com.megamaced.nccollectives.ui.screen.home.HomeScreen
import com.megamaced.nccollectives.ui.screen.home.HomeViewModel
import com.megamaced.nccollectives.ui.screen.home.collective
import com.megamaced.nccollectives.ui.screen.home.listItem
import com.megamaced.nccollectives.ui.screen.search.SearchScreen
import com.megamaced.nccollectives.ui.screen.search.SearchViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each tab's root screen, given a tap on its tab while it is showing: back
 * to the top of its list, and in Search, into the field. The screens are
 * the real ones, on ViewModels over mocked repositories.
 */
@RunWith(AndroidJUnit4::class)
class RootScreenReselectTest {
    @get:Rule
    val rule = createComposeRule()

    private val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun show(content: @Composable () -> Unit) {
        rule.setContent(content)
        rule.waitForIdle()
    }

    private fun tap() {
        rule.runOnIdle { assertTrue("the screen is listening", taps.tryEmit(Unit)) }
        rule.waitForIdle()
    }

    /** How far the screen's list is scrolled; 0 at its top. */
    private fun listOffset(): Float =
        rule
            .onNode(hasScrollToIndexAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    /** Scroll to [lastText], tap the tab, and check the list went back to its top. */
    private fun checkGoesBackToTheTop(lastText: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(lastText))
        rule.waitForIdle()
        assertTrue("scrolled down first", listOffset() > 0f)

        tap()

        assertEquals(0f, listOffset())
    }

    @Test
    fun home() {
        val pageRepository = mockk<PageRepository>(relaxed = true) {
            every { observeAttention() } returns flowOf(PageAttention(emptyList(), emptyList()))
            every { observeRecentlyViewed(any()) } returns flowOf(emptyList())
            every { observeRecentPagesAcrossCollectives(any()) } returns flowOf((1L..8L).map { listItem(it) })
            every { observeTagCounts() } returns flowOf((1..12).map { TagCount("tag$it", 1) })
        }
        val collectiveRepository = mockk<CollectiveRepository>(relaxed = true) {
            every { observeCollectives() } returns MutableStateFlow(listOf(collective(1)))
        }
        val currentUser = mockk<CurrentUser> {
            every { id } returns MutableStateFlow(null)
            coEvery { refreshIfUnknown() } just runs
        }
        val alertRepository = mockk<AlertRepository> { every { observeUnseenCount() } returns flowOf(0) }
        val viewModel =
            HomeViewModel(SavedStateHandle(), collectiveRepository, pageRepository, currentUser, mockk(relaxed = true), alertRepository)
        show {
            HomeScreen(
                innerPadding = PaddingValues(),
                onOpenPage = {},
                onOpenSettings = {},
                onSeeAll = { _, _ -> },
                onBrowseTag = {},
                onOpenAlerts = {},
                reselected = taps,
                viewModel = viewModel,
            )
        }

        checkGoesBackToTheTop("tag12 · 1")
    }

    @Test
    fun collectives() {
        val repository = mockk<CollectiveRepository>(relaxed = true) {
            every { observeCollectives() } returns MutableStateFlow((1L..40L).map { collective(it, name = "C%02d".format(it)) })
        }
        val preferences = mockk<UserPreferences>(relaxed = true) { every { flow } returns flowOf(UserPrefs()) }
        val viewModel = CollectiveListViewModel(repository, mockk(relaxed = true), preferences, mockk(relaxed = true))
        show {
            CollectiveListScreen(
                innerPadding = PaddingValues(),
                onCollectiveClick = {},
                onOpenTrash = {},
                reselected = taps,
                viewModel = viewModel,
            )
        }

        checkGoesBackToTheTop("C40")
    }

    @Test
    fun favorites() {
        val viewModel = FavoritesViewModel(favoritesCollectives(), favoritesPages())
        show { FavoritesScreen(innerPadding = PaddingValues(), onOpenPage = {}, reselected = taps, viewModel = viewModel) }

        checkGoesBackToTheTop("F40")
    }

    @Test
    fun alerts() {
        val repository = mockk<AlertRepository>(relaxed = true) {
            every { observeAlerts() } returns MutableStateFlow((1L..40L).map { alert(it, seen = true, subject = "A%02d".format(it)) })
            every { observeSupported() } returns MutableStateFlow(true)
            coEvery { refresh() } returns ApiResult.Success(Unit)
        }
        val collectives = mockk<CollectiveRepository>(relaxed = true) {
            every { observeCollectives() } returns MutableStateFlow(emptyList())
        }
        val viewModel = AlertsViewModel(repository, collectives, mockk(relaxed = true), AccountGeneration())
        show {
            AlertsScreen(innerPadding = PaddingValues(), onOpenPage = {}, onOpenCollective = {}, reselected = taps, viewModel = viewModel)
        }

        checkGoesBackToTheTop("A40")
    }

    @Test
    fun searchFocusesItsField() {
        val browsed = mutableListOf<String>()
        show {
            SearchScreen(
                PaddingValues(),
                onOpenPage = {},
                onBrowseTag = { browsed += it },
                reselected = taps,
                viewModel = searchViewModel(),
            )
        }
        rule.onNode(hasSetTextAction()).assertIsNotFocused()

        tap()

        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun searchOffersTagsBeforeAnythingIsTyped_andOneOpensItsPages() {
        val browsed = mutableListOf<String>()
        show {
            SearchScreen(
                PaddingValues(),
                onOpenPage = {},
                onBrowseTag = { browsed += it },
                reselected = taps,
                viewModel = searchViewModel(),
            )
        }

        rule.onNodeWithText("Recent").assertExists()
        rule.onNodeWithText("tag1 · 3").performClick()

        rule.runOnIdle { assertEquals(listOf("tag1"), browsed) }
    }

    @Test
    fun anUnstarredFavoriteCanBePutBack() {
        val collectives = favoritesCollectives()
        coEvery { collectives.toggleFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
        val viewModel = FavoritesViewModel(collectives, favoritesPages())
        show { FavoritesScreen(innerPadding = PaddingValues(), onOpenPage = {}, reselected = taps, viewModel = viewModel) }

        rule.onNodeWithContentDescription("Remove F01 from favorites").performClick()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()

        coVerify { collectives.toggleFavorite(collectiveId = 1, pageId = 1, favorite = false) }
        coVerify { collectives.toggleFavorite(collectiveId = 1, pageId = 1, favorite = true) }
    }

    @Test
    fun aScrollInterruptedByTheUserDoesNotStopTheNextTap() {
        // Touching the list cancels the coroutine the animated scroll runs
        // in. Run directly in the listener, that ended the listening, and
        // every later tap did nothing.
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        show {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            OnReselected(taps) { list.scrollToTop() }
            LazyColumn(state = list) { items(200) { Text("Row $it", Modifier.height(48.dp)) } }
        }
        rule.runOnIdle { scope.launch { list.scrollToItem(100) } }
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { taps.tryEmit(Unit) }
        rule.mainClock.advanceTimeByFrame()
        // What a finger on the list does to a scroll in progress.
        rule.runOnIdle { scope.launch { list.scroll(MutatePriority.PreventUserInput) { } } }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle { scope.launch { list.scrollToItem(100) } }
        rule.waitForIdle()

        tap()

        rule.runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
    }

    @Test
    fun aSecondUnstarIsOfferedAtOnce_inPlaceOfTheFirst() {
        val collectives = favoritesCollectives()
        coEvery { collectives.toggleFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
        val viewModel = FavoritesViewModel(collectives, favoritesPages())
        show { FavoritesScreen(innerPadding = PaddingValues(), onOpenPage = {}, reselected = taps, viewModel = viewModel) }

        rule.onNodeWithContentDescription("Remove F01 from favorites").performClick()
        rule.onNodeWithText("Removed “F01” from favorites").assertExists()
        rule.onNodeWithContentDescription("Remove F02 from favorites").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Removed “F02” from favorites").assertExists()
        rule.onNodeWithText("Removed “F01” from favorites").assertDoesNotExist()
    }

    private fun favoritesCollectives() =
        mockk<CollectiveRepository>(relaxed = true) {
            every { observeCollectives() } returns
                MutableStateFlow(listOf(collective(1, name = "Wiki", favoritePageIds = (1L..40L).toSet())))
        }

    private fun favoritesPages() =
        mockk<PageRepository>(relaxed = true) {
            every { observePageList(1) } returns flowOf((1L..40L).map { listItem(it, title = "F%02d".format(it)) })
        }

    private fun searchViewModel(): SearchViewModel {
        val collectives = mockk<CollectiveRepository>(relaxed = true) {
            every { observeCollectives() } returns MutableStateFlow(emptyList())
        }
        val pages = mockk<PageRepository>(relaxed = true) {
            every { observeTagCounts() } returns flowOf(listOf(TagCount("tag1", 3), TagCount("tag2", 1)))
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { flow } returns flowOf(UserPrefs(recentSearches = listOf("soup")))
        }
        return SearchViewModel(mockk(relaxed = true), collectives, pages, preferences)
    }
}
