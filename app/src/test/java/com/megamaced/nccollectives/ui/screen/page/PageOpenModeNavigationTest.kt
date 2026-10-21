package com.megamaced.nccollectives.ui.screen.page

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.PageOpenMode
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.navigation.TopLevelTab
import com.megamaced.nccollectives.ui.navigation.navigateFrom
import com.megamaced.nccollectives.ui.navigation.navigateToTab
import com.megamaced.nccollectives.ui.navigation.popBackStackFrom
import com.megamaced.nccollectives.ui.screen.home.collective
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edit mode's trip into the editor, through a real NavController: the real
 * page and editor routes, the nav host's own [navigateFrom] and
 * [popBackStackFrom] — which drop a navigation from an entry that isn't
 * resumed — and the real screen and view model over fakes. The page is
 * cached from the start, so the decision is ready while the page is still
 * animating in, which is when a trip taken too eagerly would be dropped.
 */
@RunWith(AndroidJUnit4::class)
class PageOpenModeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private var viewModel: PageViewModel? = null

    private val tab = TopLevelTab.Collectives
    private val shown = MutableStateFlow<Page?>(page())
    private val collectives = MutableStateFlow(listOf(collective(COLLECTIVE)))
    private val prefs = MutableStateFlow(UserPrefs(pageOpenMode = PageOpenMode.Edit))

    /** Each version recorded as viewed: null for a view that read no version. */
    private val recorded = mutableListOf<Long?>()

    /** Holds back the collectives, and so the visit's decision, when set. */
    private var collectivesHeld: CompletableDeferred<Unit>? = null

    /** Holds the collaborative editor's capability check open, when set. */
    private var capabilityCheck: CompletableDeferred<Boolean>? = null

    private val pages = mockk<PageRepository>(relaxed = true) {
        every { observePage(PAGE) } returns shown
        every { observeBacklinksFor(any(), any()) } returns flowOf(emptyList())
        every { observeSubpages(any(), any()) } returns flowOf(emptyList())
        coEvery { refreshBodyIfChanged(PAGE) } returns ApiResult.Success(false)
        coEvery { recordView(PAGE, any(), any()) } answers { recorded += secondArg<Long?>() }
    }
    private val collectiveRepository = mockk<CollectiveRepository>(relaxed = true) {
        every { observeCollectives() } returns flow {
            collectivesHeld?.await()
            emitAll(collectives)
        }
    }
    private val attachments = mockk<AttachmentRepository>(relaxed = true) {
        every { observeForPage(any()) } returns flowOf(emptyList())
        coEvery { attachmentsBaseUrl(any()) } returns null
    }
    private val directEditing = mockk<DirectEditingRepository> {
        coEvery { isAvailable() } coAnswers { capabilityCheck?.await() ?: true }
    }
    private val userPreferences = mockk<UserPreferences> { every { flow } returns prefs }

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = TopLevelTab.Home.graphRoute) {
                navigation(route = TopLevelTab.Home.graphRoute, startDestination = TopLevelTab.Home.rootRoute) {
                    composable(TopLevelTab.Home.rootRoute) { Text("home") }
                }
                navigation(route = tab.graphRoute, startDestination = tab.rootRoute) {
                    composable(tab.rootRoute) { Text("collectives") }
                    composable(
                        route = Destination.PageView.pattern(tab),
                        arguments = listOf(navArgument(Destination.PageView.ARG_PAGE_ID) { type = NavType.LongType }),
                    ) { entry ->
                        val pageViewModel = viewModel(viewModelStoreOwner = entry) {
                            PageViewModel(
                                createSavedStateHandle(),
                                pages,
                                collectiveRepository,
                                attachments,
                                directEditing,
                                userPreferences,
                                AccountGeneration(),
                            )
                        }
                        SideEffect { viewModel = pageViewModel }
                        PageViewScreen(
                            innerPadding = PaddingValues(),
                            onBack = { nav.popBackStackFrom(entry) },
                            onEdit = {
                                nav.navigateFrom(entry, Destination.PageEdit.route(tab, PAGE)) { launchSingleTop = true }
                            },
                            onEditWeb = {
                                nav.navigateFrom(entry, Destination.PageEditWeb.route(tab, PAGE)) { launchSingleTop = true }
                            },
                            onAttachments = {},
                            onOpenPage = {},
                            onBrowseTag = { _, _ -> },
                            viewModel = pageViewModel,
                        )
                    }
                    composable(
                        route = Destination.PageEdit.pattern(tab),
                        arguments = listOf(navArgument(Destination.PageEdit.ARG_PAGE_ID) { type = NavType.LongType }),
                    ) { Text("plain editor") }
                    composable(
                        route = Destination.PageEditWeb.pattern(tab),
                        arguments = listOf(navArgument(Destination.PageEditWeb.ARG_PAGE_ID) { type = NavType.LongType }),
                    ) { Text("collaborative editor") }
                }
            }
        }
    }

    private fun openPage() {
        show()
        compose.runOnIdle { nav.navigateToTab(tab) }
        compose.runOnIdle { nav.navigate(Destination.PageView.route(tab, PAGE)) }
        compose.waitForIdle()
    }

    private fun here(): String? = nav.currentBackStackEntry?.destination?.route

    private fun editors(): Int =
        nav.currentBackStack.value.count {
            it.destination.route == Destination.PageEdit.pattern(tab) ||
                it.destination.route == Destination.PageEditWeb.pattern(tab)
        }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun editModeOpensTheEditorOnce_andBackLandsOnThePageToRead() {
        openPage()

        assertEquals(Destination.PageEdit.pattern(tab), here())
        assertEquals(1, editors())
        assertTrue("passing through reads no version: $recorded", recorded.isNotEmpty() && recorded.all { it == null })

        back()

        assertEquals(Destination.PageView.pattern(tab), here())
        assertEquals(0, editors())
        assertEquals(PageOpenState.Reading, viewModel!!.openState.value)
        assertTrue("back on the page, it is read: $recorded", VERSION in recorded)
    }

    @Test
    fun editModeTakesTheCollaborativeEditorWhenThatIsTheOneTheEditButtonWouldOpen() {
        prefs.value = prefs.value.copy(editorPreference = EditorPreference.PreferCollaborative)

        openPage()

        assertEquals(Destination.PageEditWeb.pattern(tab), here())
    }

    @Test
    fun readModeLeavesThePageOpenForReading() {
        prefs.value = UserPrefs(pageOpenMode = PageOpenMode.Read)

        openPage()

        assertEquals(Destination.PageView.pattern(tab), here())
        assertEquals(PageOpenState.Reading, viewModel!!.openState.value)
        assertTrue(VERSION in recorded)
    }

    @Test
    fun aPageWithAnUnresolvedConflictOpensForReading() {
        shown.value = page(draft = "My version")

        openPage()

        assertEquals(Destination.PageView.pattern(tab), here())
        assertEquals(PageOpenState.Reading, viewModel!!.openState.value)
    }

    @Test
    fun aPageInACollectiveTheUserCannotEditOpensForReading() {
        collectives.value = listOf(collective(COLLECTIVE, canEdit = false))

        openPage()

        assertEquals(Destination.PageView.pattern(tab), here())
    }

    @Test
    fun aTrashedPageOpensForReading() {
        shown.value = page(trashed = true)

        openPage()

        assertEquals(Destination.PageView.pattern(tab), here())
    }

    @Test
    fun aPageThatGoesAwayWhileTheEditorIsWorkedOut_isNotEdited() {
        prefs.value = prefs.value.copy(editorPreference = EditorPreference.PreferCollaborative)
        val check = CompletableDeferred<Boolean>().also { capabilityCheck = it }

        openPage()
        compose.runOnIdle {
            shown.value = null
            check.complete(true)
        }
        compose.waitForIdle()

        assertEquals(Destination.PageView.pattern(tab), here())
        assertEquals(0, editors())
        assertEquals(PageOpenState.Reading, viewModel!!.openState.value)
    }

    @Test
    fun anEditTappedWhileTheTripIsWorkedOutOpensOneEditor() {
        prefs.value = prefs.value.copy(editorPreference = EditorPreference.PreferCollaborative)
        val check = CompletableDeferred<Boolean>().also { capabilityCheck = it }

        openPage()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.runOnIdle { check.complete(true) }
        compose.waitForIdle()

        assertEquals(Destination.PageEditWeb.pattern(tab), here())
        assertEquals(1, editors())
    }

    @Test
    fun anEditTappedBeforeTheVisitIsDecided_isNotFollowedByAnotherOnTheWayBack() {
        val held = CompletableDeferred<Unit>().also { collectivesHeld = it }

        openPage()
        assertEquals(Destination.PageView.pattern(tab), here())
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.waitForIdle()
        assertEquals(Destination.PageEdit.pattern(tab), here())
        compose.runOnIdle { held.complete(Unit) }
        compose.waitForIdle()

        back()

        assertEquals(Destination.PageView.pattern(tab), here())
        assertEquals(0, editors())
        assertEquals(PageOpenState.Reading, viewModel!!.openState.value)
    }

    @Test
    fun aPageLeftForAnotherTabAndCameBackTo_isNotEditedAgain() {
        openPage()
        back()

        compose.runOnIdle { nav.navigateToTab(TopLevelTab.Home) }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigateToTab(tab) }
        compose.waitForIdle()

        assertEquals(Destination.PageView.pattern(tab), here())
        assertEquals(0, editors())
    }

    private fun page(
        draft: String? = null,
        trashed: Boolean = false,
    ) = Page(
        id = PAGE,
        collectiveId = COLLECTIVE,
        parentId = 0,
        title = "Seasonal calendar",
        emoji = null,
        tags = emptyList(),
        subpageOrder = emptyList(),
        isFullWidth = false,
        trashed = trashed,
        serverTimestamp = VERSION,
        size = 0,
        fileName = "Seasonal calendar.md",
        filePath = "",
        collectivePath = "Field guide",
        linkedPageIds = emptyList(),
        lastUserDisplayName = "Priya",
        bodyMd = "Autumn is for fungi.",
        bodyTimestamp = VERSION,
        bodyEtag = "\"v1\"",
        bodyFromQueue = false,
        draftBodyMd = draft,
    )

    private companion object {
        const val PAGE = 12L
        const val COLLECTIVE = 3L
        const val VERSION = 1_760_000_000L
    }
}
