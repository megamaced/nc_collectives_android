package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.prefs.PageOpenMode
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.domain.model.CollectivePageMode
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.screen.home.collective
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How a visit to a page opens: decided once, when the page first arrives,
 * and kept in saved state — so a visit restored from it, after process
 * death or through a saved tab stack, carries on as it was rather than
 * deciding again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageOpenStateTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val shown = MutableStateFlow<Page?>(page())
    private val collectives = MutableStateFlow(listOf(collective(COLLECTIVE)))
    private val prefs = MutableStateFlow(UserPrefs(pageOpenMode = PageOpenMode.Edit))
    private val recorded = mutableListOf<Long?>()

    /** Holds back the collectives, and so the visit's decision, when set. */
    private var collectivesHeld: CompletableDeferred<Unit>? = null

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
    private val userPreferences = mockk<UserPreferences> { every { flow } returns prefs }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(restored: PageOpenState? = null): PageViewModel =
        PageViewModel(
            SavedStateHandle(
                buildMap {
                    put("pageId", PAGE)
                    restored?.let { put("pageView.openState", it.name) }
                },
            ),
            pages,
            collectiveRepository,
            attachments,
            mockk(relaxed = true),
            userPreferences,
            AccountGeneration(),
        )

    @Test
    fun editModeWaitsForThePageBeforeDeciding() {
        shown.value = null
        val viewModel = viewModel()
        assertNull(viewModel.openState.value)

        shown.value = page()

        assertEquals(PageOpenState.EditPending, viewModel.openState.value)
    }

    @Test
    fun theTripIntoTheEditorIsClaimedOnce() {
        val viewModel = viewModel()

        assertTrue(viewModel.claimPendingEdit())
        assertFalse(viewModel.claimPendingEdit())
        assertEquals(PageOpenState.EditOpened, viewModel.openState.value)
    }

    @Test
    fun beingInFrontLeavesAPendingTripAlone_andEndsAFinishedOne() {
        val viewModel = viewModel()

        viewModel.backInFront()
        assertEquals(PageOpenState.EditPending, viewModel.openState.value)

        viewModel.claimPendingEdit()
        viewModel.backInFront()
        assertEquals(PageOpenState.Reading, viewModel.openState.value)
    }

    @Test
    fun anEditTappedBeforeTheVisitIsDecidedSettlesItForReading() {
        val held = CompletableDeferred<Unit>().also { collectivesHeld = it }
        val viewModel = viewModel()
        assertNull(viewModel.openState.value)

        viewModel.editTapped()
        held.complete(Unit)

        assertEquals(PageOpenState.Reading, viewModel.openState.value)
        assertFalse(viewModel.claimPendingEdit())
    }

    @Test
    fun anEditTappedBeforeThePendingTripIsTakenSettlesItForReading() {
        val viewModel = viewModel()
        assertEquals(PageOpenState.EditPending, viewModel.openState.value)

        viewModel.editTapped()

        assertEquals(PageOpenState.Reading, viewModel.openState.value)
        assertFalse(viewModel.claimPendingEdit())
    }

    @Test
    fun aConflictResolvedLaterInTheVisitDoesNotStartEditing() {
        shown.value = page(draft = "My version")
        val viewModel = viewModel()
        assertEquals(PageOpenState.Reading, viewModel.openState.value)

        shown.value = page()

        assertEquals(PageOpenState.Reading, viewModel.openState.value)
        assertFalse(viewModel.claimPendingEdit())
    }

    @Test
    fun aRestoredVisitOnItsWayBackFromTheEditorIsNotDecidedAgain() {
        val viewModel = viewModel(restored = PageOpenState.EditOpened)

        assertEquals(PageOpenState.EditOpened, viewModel.openState.value)
        viewModel.backInFront()
        assertEquals(PageOpenState.Reading, viewModel.openState.value)
    }

    @Test
    fun aRestoredReadingVisitStaysForReading_whateverTheSettingNowSays() {
        val viewModel = viewModel(restored = PageOpenState.Reading)

        assertEquals(PageOpenState.Reading, viewModel.openState.value)
        assertFalse(viewModel.claimPendingEdit())
    }

    @Test
    fun aRestoredPendingTripIsStillTaken() {
        prefs.value = UserPrefs(pageOpenMode = PageOpenMode.Read)
        val viewModel = viewModel(restored = PageOpenState.EditPending)

        assertTrue(viewModel.claimPendingEdit())
    }

    @Test
    fun passingThroughOnTheWayToTheEditorReadsNoVersion() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val recording = launch { viewModel.recordViewsWhileShown() }

            viewModel.claimPendingEdit()
            assertTrue("a view, but no version read: $recorded", recorded.isNotEmpty() && recorded.all { it == null })

            viewModel.backInFront()
            assertEquals(VERSION, recorded.last())
            recording.cancel()
        }

    @Test
    fun aCollectiveSetToEditLaterLeavesADecidedVisitAlone_andTheNextVisitUsesIt() {
        prefs.value = UserPrefs(pageOpenMode = PageOpenMode.AsCollective)
        val visit = viewModel()
        assertEquals(PageOpenState.Reading, visit.openState.value)

        collectives.value = listOf(collective(COLLECTIVE, pageMode = CollectivePageMode.Edit))

        assertEquals(PageOpenState.Reading, visit.openState.value)
        assertFalse(visit.claimPendingEdit())
        assertEquals(PageOpenState.EditPending, viewModel().openState.value)
    }

    @Test
    fun theCollectivesPageModeDecides_unlessThisDeviceSaysReadOrEdit() {
        val viewing = listOf(collective(COLLECTIVE))
        val editing = listOf(collective(COLLECTIVE, pageMode = CollectivePageMode.Edit))

        assertFalse("as a collective set to view", opensIntoEditor(PageOpenMode.AsCollective, page(), viewing))
        assertTrue("as a collective set to edit", opensIntoEditor(PageOpenMode.AsCollective, page(), editing))
        assertFalse("Read over a collective set to edit", opensIntoEditor(PageOpenMode.Read, page(), editing))
        assertFalse("Read over a collective set to view", opensIntoEditor(PageOpenMode.Read, page(), viewing))
        assertTrue("Edit over a collective set to view", opensIntoEditor(PageOpenMode.Edit, page(), viewing))
        assertTrue("Edit over a collective set to edit", opensIntoEditor(PageOpenMode.Edit, page(), editing))
        assertFalse(
            "its own set to view, another set to edit",
            opensIntoEditor(PageOpenMode.AsCollective, page(), viewing + collective(COLLECTIVE + 1, pageMode = CollectivePageMode.Edit)),
        )
        assertFalse("set to edit, with a conflict", opensIntoEditor(PageOpenMode.AsCollective, page(draft = "mine"), editing))
        assertFalse(
            "set to edit, but read-only",
            opensIntoEditor(
                PageOpenMode.AsCollective,
                page(),
                listOf(collective(COLLECTIVE, canEdit = false, pageMode = CollectivePageMode.Edit)),
            ),
        )
    }

    @Test
    fun onlyALivePageInALiveCollectiveTheUserCanEditOpensStraightIntoTheEditor() {
        val editable = listOf(collective(COLLECTIVE))

        assertTrue(opensStraightIntoEditor(page(), editable))
        assertFalse("trashed page", opensStraightIntoEditor(page(trashed = true), editable))
        assertFalse("unresolved conflict", opensStraightIntoEditor(page(draft = "mine"), editable))
        assertFalse("read-only collective", opensStraightIntoEditor(page(), listOf(collective(COLLECTIVE, canEdit = false))))
        assertFalse("trashed collective", opensStraightIntoEditor(page(), listOf(collective(COLLECTIVE).copy(trashed = true))))
        assertFalse("collective gone", opensStraightIntoEditor(page(), emptyList()))
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
