package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home inside a real navigation graph, so opening a page really does take
 * Home off screen — which a recorded callback can't show.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aPageFinishingWhileAnotherFormIsOpenIsOffered_andTheFormStays() {
        val answer = CompletableDeferred<ApiResult<PageCreation>>()
        val pageRepository = mockk<PageRepository>(relaxed = true) {
            every { observeAttention() } returns flowOf(PageAttention(emptyList(), emptyList()))
            every { observeRecentlyViewed(any()) } returns flowOf(emptyList())
            every { observeRecentPagesAcrossCollectives(any()) } returns flowOf(listOf(listItem(1)))
            every { observeTagCounts() } returns flowOf(emptyList())
            every { observePageList(1) } returns flowOf(listOf(listItem(10, parentId = 0, title = "Landing")))
            coEvery { createPage(1, 10, "Fresh", "") } coAnswers { answer.await() }
        }
        val collectiveRepository = mockk<CollectiveRepository> {
            every { observeCollectives() } returns MutableStateFlow(listOf(collective(1)))
        }
        val currentUser = mockk<CurrentUser> {
            every { id } returns MutableStateFlow(null)
            coEvery { refreshIfUnknown() } just runs
        }
        val alertRepository = mockk<AlertRepository> { every { observeUnseenCount() } returns flowOf(0) }
        val viewModel =
            HomeViewModel(SavedStateHandle(), collectiveRepository, pageRepository, currentUser, mockk(relaxed = true), alertRepository)
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { entry ->
                    HomeScreen(
                        innerPadding = PaddingValues(),
                        // The RESUMED guard `navigateFrom` applies.
                        onOpenPage = { if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) nav.navigate("page") },
                        onOpenSettings = {},
                        onSeeAll = { _, _ -> },
                        onBrowseTag = {},
                        onOpenAlerts = {},
                        viewModel = viewModel,
                    )
                }
                composable("page") { Text("Created page") }
            }
        }
        rule.onNodeWithContentDescription("New page").performClick()
        rule.onNodeWithText("Title").performTextInput("Fresh")
        rule.onNodeWithText("Create").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("New page").performClick()
        rule.onNodeWithText("Title").performTextInput("Another")
        rule.onNodeWithText("Create").assertIsNotEnabled()

        rule.runOnIdle {
            answer.complete(ApiResult.Success(PageCreation(mockk { every { id } returns 50L }, SaveOutcome.Saved)))
        }
        rule.waitForIdle()

        rule.runOnIdle { assertEquals("home", nav.currentDestination?.route) }
        rule.onNodeWithText("Another").assertExists()
        rule.onNodeWithText("Created “Fresh”").assertExists()
    }
}
