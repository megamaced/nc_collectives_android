package com.megamaced.nccollectives.ui.screen.home

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
import com.megamaced.nccollectives.sync.FullSync
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric for the ICU collator the parent choices are sorted with.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val collectives = MutableStateFlow(listOf(collective(1), collective(2)))
    private val userId = MutableStateFlow<String?>(null)

    private val collectiveRepository = mockk<CollectiveRepository> {
        every { observeCollectives() } returns collectives
    }
    private val unseenAlerts = MutableStateFlow(0)
    private val alertRepository = mockk<AlertRepository> { every { observeUnseenCount() } returns unseenAlerts }
    private val pageRepository = mockk<PageRepository>(relaxed = true) {
        every { observeAttention() } returns flowOf(PageAttention(emptyList(), emptyList()))
        every { observeRecentlyViewed(any()) } returns flowOf(emptyList())
        every { observeUpdatedSinceViewed(any(), any()) } returns flowOf(listOf(listItem(41)))
        every { observeLastEditedBy(any(), any()) } returns flowOf(listOf(listItem(42)))
        every { observeRecentPagesAcrossCollectives(any()) } returns flowOf(listOf(listItem(1), listItem(2, collectiveId = 2)))
        every { observeRecentPages(any(), any()) } returns flowOf(emptyList())
        every { observeTagCounts() } returns flowOf(emptyList())
        every { observePageList(any()) } answers {
            val id = firstArg<Long>()
            flowOf(
                listOf(
                    listItem(id * 100 + 2, collectiveId = id, title = "Zed"),
                    listItem(id * 100 + 1, collectiveId = id, title = "Alpha"),
                ),
            )
        }
    }
    private val currentUser = mockk<CurrentUser> {
        every { id } returns userId
        coEvery { refreshIfUnknown() } just runs
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: Map<String, Any?> = emptyMap()) =
        HomeViewModel(
            SavedStateHandle(saved),
            collectiveRepository,
            pageRepository,
            currentUser,
            mockk<FullSync>(relaxed = true),
            alertRepository,
        )

    /** Keep the feed's `WhileSubscribed` upstream running for the test. */
    private fun TestScope.subscribe(viewModel: HomeViewModel) {
        backgroundScope.launch { viewModel.feed.collect {} }
        backgroundScope.launch { viewModel.recentFilter.collect {} }
        backgroundScope.launch { viewModel.newPageParents.collect {} }
    }

    @Test
    fun theFeedIsNullUntilTheCacheAnswers() =
        runTest(dispatcher) {
            val attention = MutableSharedFlow<PageAttention>()
            every { pageRepository.observeAttention() } returns attention
            val vm = viewModel()
            subscribe(vm)
            advanceUntilIdle()

            assertNull(vm.feed.value)

            attention.emit(PageAttention(emptyList(), emptyList()))
            advanceUntilIdle()
            assertNotNull(vm.feed.value)
        }

    @Test
    fun theFeedCarriesTheUnseenAlerts() =
        runTest(dispatcher) {
            val vm = viewModel()
            subscribe(vm)
            unseenAlerts.value = 2
            advanceUntilIdle()

            assertEquals(2, vm.feed.value?.unseenAlerts)
        }

    @Test
    fun theIdentitySectionsWaitForTheUsersId() =
        runTest(dispatcher) {
            val vm = viewModel()
            subscribe(vm)
            advanceUntilIdle()

            assertTrue(
                vm.feed.value!!
                    .updatedSinceRead
                    .isEmpty(),
            )
            assertTrue(
                vm.feed.value!!
                    .yourEdits
                    .isEmpty(),
            )
            verify(exactly = 0) { pageRepository.observeUpdatedSinceViewed(any(), any()) }
            verify(exactly = 0) { pageRepository.observeLastEditedBy(any(), any()) }

            userId.value = "me"
            advanceUntilIdle()

            assertEquals(
                listOf(41L),
                vm.feed.value!!
                    .updatedSinceRead
                    .map { it.id },
            )
            assertEquals(
                listOf(42L),
                vm.feed.value!!
                    .yourEdits
                    .map { it.id },
            )
            verify { pageRepository.observeUpdatedSinceViewed("me", HOME_PREVIEW_ROWS + 1) }
            verify { pageRepository.observeLastEditedBy("me", HOME_PREVIEW_ROWS + 1) }
        }

    @Test
    fun previewsAskForOneRowMoreThanTheyShow() =
        runTest(dispatcher) {
            subscribe(viewModel())
            advanceUntilIdle()

            verify { pageRepository.observeRecentlyViewed(HOME_PREVIEW_CARDS + 1) }
            verify { pageRepository.observeRecentPagesAcrossCollectives(HOME_PREVIEW_RECENT + 1) }
        }

    @Test
    fun choosingACollectiveWithNothingRecentLeavesHomeNotEmpty() =
        runTest(dispatcher) {
            val vm = viewModel()
            subscribe(vm)
            advanceUntilIdle()

            vm.setRecentFilter(2)
            advanceUntilIdle()

            assertEquals(2L, vm.recentFilter.value)
            assertTrue(
                vm.feed.value!!
                    .recentlyUpdated
                    .isEmpty(),
            )
            verify { pageRepository.observeRecentPages(2, HOME_PREVIEW_RECENT + 1) }
            assertFalse(vm.feed.value!!.isEmpty(recentFiltered = vm.recentFilter.value != null))

            vm.setRecentFilter(null)
            advanceUntilIdle()
            assertEquals(
                listOf(1L, 2L),
                vm.feed.value!!
                    .recentlyUpdated
                    .map { it.id },
            )
        }

    @Test
    fun aSavedFilterOnAGoneCollectiveFallsBackToAll_andIsForgotten() =
        runTest(dispatcher) {
            val saved = SavedStateHandle(mapOf("recentFilter" to 99L))
            val vm = HomeViewModel(saved, collectiveRepository, pageRepository, currentUser, mockk(relaxed = true), alertRepository)
            subscribe(vm)
            advanceUntilIdle()

            assertNull(vm.recentFilter.value)
            assertEquals(
                listOf(1L, 2L),
                vm.feed.value!!
                    .recentlyUpdated
                    .map { it.id },
            )
            verify(exactly = 0) { pageRepository.observeRecentPages(99, any()) }
            // A collective 99 restored later must not bring the filter back.
            assertEquals(ALL_COLLECTIVES, saved.get<Long>("recentFilter"))
        }

    @Test
    fun theOneWritableCollectiveIsChosenForTheUser_butNotOneOfSeveral() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.startNewPage(listOf(collective(1)))
            assertEquals(1L, vm.newPageCollectiveId.value)

            vm.startNewPage(listOf(collective(1), collective(2)))
            assertNull(vm.newPageCollectiveId.value)
        }

    @Test
    fun parentsCarryTheCollectiveTheyAreFrom_inTitleOrder() =
        runTest(dispatcher) {
            val vm = viewModel()
            subscribe(vm)
            vm.selectNewPageCollective(2)
            advanceUntilIdle()

            val parents = vm.newPageParents.value!!
            assertEquals(2L, parents.collectiveId)
            assertEquals(listOf("Alpha", "Zed"), parents.choices.map { it.title })
        }

    @Test
    fun aSecondCreateWhileTheFirstIsInFlightIsDropped() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<ApiResult<PageCreation>>()
            coEvery { pageRepository.createPage(any(), any(), any(), any()) } coAnswers { answer.await() }
            val vm = viewModel()

            vm.createPage(collectiveId = 1, parentId = 101, title = "New", visit = 1)
            vm.createPage(collectiveId = 1, parentId = 101, title = "New", visit = 1)
            advanceUntilIdle()
            answer.complete(created(50))
            advanceUntilIdle()

            coVerify(exactly = 1) { pageRepository.createPage(any(), any(), any(), any()) }

            // Released once it has finished.
            coEvery { pageRepository.createPage(any(), any(), any(), any()) } returns created(51)
            vm.createPage(collectiveId = 1, parentId = 101, title = "Another", visit = 1)
            advanceUntilIdle()
            coVerify(exactly = 2) { pageRepository.createPage(any(), any(), any(), any()) }
        }

    @Test
    fun theCreateSendsWhatTheDialogShowed_andTheResultNamesTheVisitThatAsked() =
        runTest(dispatcher) {
            coEvery { pageRepository.createPage(any(), any(), any(), any()) } returns created(50)
            val vm = viewModel()
            val visit = vm.beginVisit()
            // Moving the dialog's collective afterwards changes nothing already sent.
            vm.selectNewPageCollective(1)

            vm.createPage(collectiveId = 2, parentId = 201, title = "  New  ", visit = visit)
            vm.selectNewPageCollective(1)
            advanceUntilIdle()

            coVerify { pageRepository.createPage(2, 201, "New", "") }
            val createdPage = vm.createdPages.first()
            assertEquals(CreatedPage(pageId = 50, title = "New", visit = visit), createdPage)
            assertTrue("a later visit is a different one", vm.beginVisit() != visit)
        }

    private fun created(id: Long) =
        ApiResult.Success(
            PageCreation(page = mockk { every { this@mockk.id } returns id }, bodyOutcome = SaveOutcome.Saved),
        )
}
