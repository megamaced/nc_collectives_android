package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.prefs.PageOpenMode
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.util.uiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PageReaderRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val page = mockk<Page>(relaxed = true) {
        every { bodyMd } returns "Cached body"
        every { draftBodyMd } returns null
    }
    private val pages = mockk<PageRepository> {
        every { observePage(PAGE_ID) } returns flowOf(page)
        coEvery { getPage(PAGE_ID) } returns page
        coEvery { refreshBodyIfChanged(PAGE_ID) } returns ApiResult.Success(false)
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
    fun checksStartAfterOneIntervalAndOnlyRunWhileResumed() =
        runTest(dispatcher) {
            val reader = viewModel()
            val lifecycle = showReader(reader, Lifecycle.State.STARTED)
            tick(2 * INTERVAL_MS)
            verifyRefreshes(1) // The cold-open request still runs.

            lifecycle.currentState = Lifecycle.State.RESUMED
            tick(INTERVAL_MS - 1)
            verifyRefreshes(1)
            tick(1)
            verifyRefreshes(2)
            tick()
            verifyRefreshes(3)

            lifecycle.currentState = Lifecycle.State.STARTED
            tick(2 * INTERVAL_MS)
            verifyRefreshes(3)
            lifecycle.currentState = Lifecycle.State.RESUMED
            tick()
            verifyRefreshes(4)
        }

    @Test
    fun aPendingCheckDoesNotOverlapAndIsCancelledOnPause() =
        runTest(dispatcher) {
            val reader = viewModel()
            val lifecycle = showReader(reader)
            val release = CompletableDeferred<ApiResult<Boolean>>()
            var cancelled = false
            coEvery { pages.refreshBodyIfChanged(PAGE_ID) } coAnswers {
                try {
                    release.await()
                } finally {
                    cancelled = !release.isCompleted
                }
            }

            tick(2 * INTERVAL_MS)
            verifyRefreshes(2)
            assertFalse(reader.uiState.value.isRefreshingBody)
            reader.refreshBody()
            runCurrent()
            assertTrue(reader.uiState.value.isRefreshingBody)
            verifyRefreshes(2)

            lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertTrue(cancelled)
            assertFalse(reader.uiState.value.isRefreshingBody)
            assertFalse(reader.uiState.value.isLoadingBody)

            reader.refreshBody()
            runCurrent()
            verifyRefreshes(3)
            release.complete(ApiResult.Success(true))
            runCurrent()
            assertEquals(uiText(R.string.page_updated), reader.uiState.value.statusMessage)
        }

    @Test
    fun aManualRefreshReportsTheResultOfAnInFlightCheck() =
        runTest(dispatcher) {
            val reader = viewModel()
            showReader(reader)
            val release = CompletableDeferred<ApiResult<Boolean>>()
            coEvery { pages.refreshBodyIfChanged(PAGE_ID) } coAnswers { release.await() }

            tick()
            reader.refreshBody()
            runCurrent()
            verifyRefreshes(2)
            release.complete(ApiResult.Success(false))
            runCurrent()

            assertFalse(reader.uiState.value.isRefreshingBody)
            assertEquals(uiText(R.string.page_up_to_date), reader.uiState.value.statusMessage)
        }

    @Test
    fun automaticChecksSkipLocalEditsAndStayQuietWhenOffline() =
        runTest(dispatcher) {
            val reader = viewModel()
            showReader(reader)
            every { page.bodyFromQueue } returns true
            tick()
            verifyRefreshes(1)
            reader.refreshBody()
            runCurrent()
            verifyRefreshes(2)
            reader.dismissStatus()

            every { page.bodyFromQueue } returns false
            every { page.draftBodyMd } returns "Conflict draft"
            tick()
            verifyRefreshes(2)

            every { page.draftBodyMd } returns null
            coEvery { pages.refreshBodyIfChanged(PAGE_ID) } returns ApiResult.NetworkError(IOException("offline"))
            tick()
            verifyRefreshes(3)
            assertNull(reader.uiState.value.errorMessage)
            assertNull(reader.uiState.value.statusMessage)
        }

    private fun viewModel() =
        PageViewModel(
            SavedStateHandle(mapOf(Destination.PageView.ARG_PAGE_ID to PAGE_ID)),
            pages,
            mockk { every { observeCollectives() } returns flowOf(emptyList()) },
            mockk {
                every { observeForPage(PAGE_ID) } returns flowOf(emptyList())
                coEvery { attachmentsBaseUrl(PAGE_ID) } returns null
            },
            mockk(),
            mockk<UserPreferences> { every { flow } returns flowOf(UserPrefs(pageOpenMode = PageOpenMode.Read)) },
            AccountGeneration(),
        )

    private fun TestScope.showReader(
        reader: PageViewModel,
        state: Lifecycle.State = Lifecycle.State.RESUMED,
    ): LifecycleRegistry {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = state
        backgroundScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { reader.refreshBodyWhileReading() }
        }
        runCurrent()
        return owner.lifecycle
    }

    private fun TestScope.tick(interval: Long = INTERVAL_MS) {
        advanceTimeBy(interval)
        runCurrent()
    }

    private fun verifyRefreshes(count: Int) {
        coVerify(exactly = count) { pages.refreshBodyIfChanged(PAGE_ID) }
    }

    private companion object {
        const val PAGE_ID = 41L
        const val INTERVAL_MS = 30_000L
    }
}
