package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.DraftBase
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #57: the native editor saves its draft against what it was seeded
 * from, which it keeps beside the draft — across process death too, when
 * coming back revalidates the page and moves the cache on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageEditDraftBaseTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PageRepository>(relaxed = true)
    private val attachments = mockk<AttachmentRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.saveBody(any(), any(), any()) } returns SaveOutcome.Saved
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun cachedAs(
        body: String,
        etag: String,
    ) {
        coEvery { repository.getPage(PAGE_ID) } returns page(body, etag)
    }

    @Test
    fun aDraftRestoredAfterProcessDeathIsSavedAgainstTheVersionItWasWrittenFrom() =
        runTest(dispatcher) {
            cachedAs("A's text", "A")
            coEvery { repository.refreshBodyIfChanged(PAGE_ID) } returns ApiResult.Success(false)
            val before = SavedStateHandle(mapOf(Destination.PageEdit.ARG_PAGE_ID to PAGE_ID))
            val first = PageEditViewModel(before, repository, attachments)
            advanceUntilIdle()
            first.onBodyChanged("my edit of A")

            // The process dies; meanwhile someone else saves version B, and
            // coming back revalidates the page onto it.
            val restored = SavedStateHandle(before.keys().associateWith { before.get<Any?>(it) })
            cachedAs("B's text", "B")
            coEvery { repository.refreshBodyIfChanged(PAGE_ID) } returns ApiResult.Success(true)
            val editor = PageEditViewModel(restored, repository, attachments)
            advanceUntilIdle()
            editor.save()
            advanceUntilIdle()

            coVerify { repository.saveBody(PAGE_ID, "my edit of A", DraftBase.of("A's text", "A")) }
        }

    @Test
    fun onceASaveLands_theNextIsAgainstTheCache() =
        runTest(dispatcher) {
            cachedAs("A's text", "A")
            coEvery { repository.refreshBodyIfChanged(PAGE_ID) } returns ApiResult.Success(false)
            val editor = PageEditViewModel(SavedStateHandle(mapOf(Destination.PageEdit.ARG_PAGE_ID to PAGE_ID)), repository, attachments)
            advanceUntilIdle()
            editor.onBodyChanged("first")
            editor.save()
            advanceUntilIdle()
            editor.onBodyChanged("second")
            editor.save()
            advanceUntilIdle()

            coVerify { repository.saveBody(PAGE_ID, "first", DraftBase.of("A's text", "A")) }
            coVerify { repository.saveBody(PAGE_ID, "second", null) }
        }

    @Test
    fun aDraftFilledWithAQueuedEdit_isBasedOnItsTextAndNoServerVersion() =
        runTest(dispatcher) {
            coEvery { repository.getPage(PAGE_ID) } returns page("Queued A", "A", fromQueue = true)
            coEvery { repository.refreshBodyIfChanged(PAGE_ID) } returns ApiResult.Success(false)
            val editor = PageEditViewModel(SavedStateHandle(mapOf(Destination.PageEdit.ARG_PAGE_ID to PAGE_ID)), repository, attachments)
            advanceUntilIdle()
            editor.onBodyChanged("Queued A, edited")
            editor.save()
            advanceUntilIdle()

            coVerify { repository.saveBody(PAGE_ID, "Queued A, edited", DraftBase.of("Queued A", null)) }
        }

    private fun page(
        body: String,
        etag: String,
        fromQueue: Boolean = false,
    ) = Page(
        id = PAGE_ID,
        collectiveId = 1,
        parentId = 0,
        title = "Page",
        emoji = null,
        tags = emptyList(),
        subpageOrder = emptyList(),
        isFullWidth = false,
        trashed = false,
        serverTimestamp = 0,
        size = 0,
        fileName = "Page.md",
        filePath = "",
        collectivePath = "",
        linkedPageIds = emptyList(),
        lastUserDisplayName = "",
        bodyMd = body,
        bodyTimestamp = 100L,
        bodyEtag = etag,
        bodyFromQueue = fromQueue,
        draftBodyMd = null,
    )

    private companion object {
        const val PAGE_ID = 12L
    }
}
