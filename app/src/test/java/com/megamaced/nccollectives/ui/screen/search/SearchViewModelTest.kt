package com.megamaced.nccollectives.ui.screen.search

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.model.SearchHit
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.domain.repository.SearchRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<SearchRepository>()
    private val preferences = mockk<UserPreferences>(relaxUnitFun = true) {
        every { flow } returns emptyFlow()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun searchDebouncesAndShowsTitlesWhileWaitingForContent() =
        runTest(dispatcher) {
            val title = SearchHit("Proxy notes", null, 10, 7)
            val body = SearchHit("Runbook", "proxy reference", 20, 7)
            val complete = CompletableDeferred<Unit>()
            coEvery { repository.search("proxy", any(), any()) } coAnswers {
                thirdArg<(List<SearchHit>) -> Unit>()(listOf(title))
                complete.await()
                ApiResult.Success(listOf(title, body))
            }
            val vm = viewModel()

            vm.onQueryChanged("p")
            advanceTimeBy(350)
            runCurrent()
            coVerify(exactly = 0) { repository.search(any(), any(), any()) }
            vm.onQueryChanged(" proxy ")
            advanceTimeBy(349)
            coVerify(exactly = 0) { repository.search(any(), any(), any()) }
            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf(title), vm.uiState.value.results)
            assertTrue(vm.uiState.value.isSearching)
            coVerify(exactly = 0) { preferences.pushRecentSearch(any()) }

            complete.complete(Unit)
            runCurrent()

            assertEquals(listOf(title, body), vm.uiState.value.results)
            assertFalse(vm.uiState.value.isSearching)
            coVerify(exactly = 1) { preferences.pushRecentSearch("proxy") }
        }

    @Test
    fun changingOrClearingTheQueryCancelsAnInFlightContentSearch() =
        runTest(dispatcher) {
            val cancelled = mutableListOf<String>()
            coEvery { repository.search(any(), any(), any()) } coAnswers {
                val term = firstArg<String>()
                thirdArg<(List<SearchHit>) -> Unit>()(listOf(SearchHit(term, null, 10, 7)))
                try {
                    awaitCancellation()
                } finally {
                    cancelled += term
                }
            }
            val vm = viewModel()

            vm.onQueryChanged("first")
            advanceTimeBy(350)
            runCurrent()
            val first = vm.uiState.value
            assertEquals("first", first.results.single().title)

            vm.onQueryChanged("second")
            runCurrent()
            assertEquals(listOf("first"), cancelled)
            assertEquals(emptyList<SearchHit>(), vm.uiState.value.results)
            advanceTimeBy(350)
            runCurrent()
            val second = vm.uiState.value
            assertEquals("second", second.results.single().title)

            vm.onQueryChanged("")
            runCurrent()
            assertEquals(listOf("first", "second"), cancelled)
            val cleared = vm.uiState.value
            assertTrue(cleared.results.isEmpty())
            assertFalse(cleared.isSearching)
            assertNull(cleared.errorMessage)
            coVerify(exactly = 0) { preferences.pushRecentSearch(any()) }
        }

    private fun viewModel() =
        SearchViewModel(
            repository,
            mockk<CollectiveRepository> { every { observeCollectives() } returns emptyFlow() },
            mockk<PageRepository> { every { observeTagCounts() } returns emptyFlow() },
            preferences,
        )
}
