package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.domain.model.PageAttention
import com.megamaced.nccollectives.domain.model.PageCreation
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AlertRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The New page button end to end on Home, and where the created page goes:
 * opened when the visit that asked for it is still on screen, offered when
 * the user has left and come back since.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenCreateTest {
    @get:Rule
    val rule = createComposeRule()

    private val answer = CompletableDeferred<ApiResult<PageCreation>>()
    private val unseenAlerts = MutableStateFlow(0)
    private val alertRepository = mockk<AlertRepository> { every { observeUnseenCount() } returns unseenAlerts }
    private val pageRepository = mockk<PageRepository>(relaxed = true) {
        every { observeAttention() } returns flowOf(PageAttention(emptyList(), emptyList()))
        every { observeRecentlyViewed(any()) } returns flowOf(emptyList())
        every { observeRecentPagesAcrossCollectives(any()) } returns flowOf(listOf(listItem(1)))
        every { observeTagCounts() } returns flowOf(emptyList())
        every { observePageList(1) } returns flowOf(listOf(listItem(10, parentId = 0, title = "Landing")))
        coEvery { createPage(1, 10, "Fresh", "") } coAnswers { answer.await() }
    }
    private val collectiveRepository = mockk<CollectiveRepository> {
        every { observeCollectives() } returns MutableStateFlow(listOf(collective(1)))
    }
    private val currentUser = mockk<CurrentUser> {
        every { id } returns MutableStateFlow(null)
        coEvery { refreshIfUnknown() } just runs
    }

    private lateinit var viewModel: HomeViewModel
    private var showHome by mutableStateOf(true)
    private val opened = mutableListOf<Long>()

    @Before
    fun setUp() {
        viewModel =
            HomeViewModel(SavedStateHandle(), collectiveRepository, pageRepository, currentUser, mockk(relaxed = true), alertRepository)
        rule.setContent {
            if (showHome) {
                HomeScreen(
                    innerPadding = PaddingValues(),
                    onOpenPage = { opened += it },
                    onOpenSettings = {},
                    onSeeAll = { _, _ -> },
                    onBrowseTag = {},
                    onOpenAlerts = {},
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun createFresh() {
        rule.onNodeWithContentDescription("New page").performClick()
        // The only collective the user can write to is already chosen, and
        // its landing page is the default parent.
        rule.onNodeWithText("Landing").assertExists()
        rule.onNodeWithText("Title").performTextInput("Fresh")
        rule.onNodeWithText("Create").performClick()
        rule.waitForIdle()
    }

    private fun succeed() {
        answer.complete(
            ApiResult.Success(PageCreation(page = mockk { every { id } returns 50L }, bodyOutcome = SaveOutcome.Saved)),
        )
        rule.waitForIdle()
    }

    @Test
    fun aPageCreatedWhileHomeIsUpOpens() {
        createFresh()
        succeed()

        assertEquals(listOf(50L), opened)
    }

    @Test
    fun aPageCreatedAfterTheUserLeftIsOffered_notOpened() {
        createFresh()
        showHome = false
        rule.waitForIdle()
        succeed()
        showHome = true
        rule.waitForIdle()

        assertTrue("coming back must not be hijacked, opened $opened", opened.isEmpty())
        rule.onNodeWithText("Created “Fresh”").assertExists()
        rule.onNodeWithText("Open").performClick()
        rule.waitForIdle()
        assertEquals(listOf(50L), opened)
    }

    @Test
    fun aSecondPageWaitsForTheFirst_withItsFormKept() {
        createFresh()
        rule.onNodeWithContentDescription("New page").performClick()
        rule.onNodeWithText("Title").performTextInput("Another")

        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Another").assertExists()

        succeed()
        rule.onNodeWithText("Another").assertExists()
        rule.onNodeWithText("Create").assertIsEnabled()
    }
}
