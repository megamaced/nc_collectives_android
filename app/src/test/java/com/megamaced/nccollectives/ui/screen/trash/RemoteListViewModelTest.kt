package com.megamaced.nccollectives.ui.screen.trash

import app.cash.turbine.test
import com.megamaced.nccollectives.data.api.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.IOException

/** Row stand-in — the base class only ever needs an id out of `T`. */
private data class Row(
    val id: Long,
    val label: String,
)

/**
 * Test double for the four calls a real trash ViewModel supplies. Records
 * what was asked of the "server" so the guard can be checked by counting
 * requests rather than by inspecting state that a second request would
 * have overwritten anyway.
 */
private class FakeRemoteList : RemoteListViewModel<Row>() {
    var loadResult: ApiResult<List<Row>> = ApiResult.Success(emptyList())
    var restoreResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var purgeResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var loadCalls = 0
    val restored = mutableListOf<Long>()
    val purged = mutableListOf<Long>()

    override val restoredMessage = "Row restored"
    override val purgedMessage = "Row permanently deleted"

    override fun idOf(item: Row): Long = item.id

    override suspend fun load(): ApiResult<List<Row>> {
        loadCalls++
        return loadResult
    }

    override suspend fun restoreItem(id: Long): ApiResult<Unit> {
        restored += id
        return restoreResult
    }

    override suspend fun purgeItem(id: Long): ApiResult<Unit> {
        purged += id
        return purgeResult
    }
}

/**
 * Unit tests for [RemoteListViewModel], which both trash screens are now
 * built out of — so a regression here is a regression in two screens, one of
 * which permanently destroys data.
 *
 * The two behaviours worth pinning are the ones the duplicated versions each
 * got right by accident: the re-entry guard (a second pull-to-refresh must
 * not fan out a second listing) and the optimistic removal (the restored or
 * purged row leaves the snapshot, because nothing observes trashed rows and
 * nothing else will take it out).
 *
 * `StandardTestDispatcher` rather than `UnconfinedTestDispatcher` on purpose:
 * the guard only exists for the window where the load has been *started* and
 * hasn't finished, and an unconfined dispatcher runs the body eagerly and
 * closes that window before the test can look at it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // `viewModelScope` is `Dispatchers.Main.immediate`; without this
        // every launch below would throw for want of a main looper.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refresh_whileALoadIsInFlight_isIgnored() =
        runTest(dispatcher) {
            val viewModel = FakeRemoteList()
            viewModel.loadResult = ApiResult.Success(listOf(Row(1, "one")))

            viewModel.refresh()
            // The guard flag is set synchronously; the load itself is queued.
            assertTrue(viewModel.uiState.value.isLoading)
            viewModel.refresh()
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, viewModel.loadCalls)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun refresh_afterTheLoadFinishes_runsAgain() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one")))

            viewModel.refresh()
            advanceUntilIdle()

            // The guard releases — it isn't a one-shot latch.
            assertEquals(2, viewModel.loadCalls)
        }

    @Test
    fun refresh_emitsLoadingThenRows() =
        runTest(dispatcher) {
            val viewModel = FakeRemoteList()
            viewModel.loadResult = ApiResult.Success(listOf(Row(7, "seven")))

            viewModel.uiState.test {
                assertEquals(emptyList<Row>(), awaitItem().items)
                viewModel.refresh()
                assertTrue(awaitItem().isLoading)
                advanceUntilIdle()
                val listed = expectMostRecentItem()
                assertFalse(listed.isLoading)
                assertEquals(listOf(7L), listed.items.map { it.id })
                assertNull(listed.errorMessage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun refresh_failure_reportsTheMessageAndKeepsTheRows() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one"), Row(2, "two")))

            viewModel.loadResult = ApiResult.HttpError(503, "Service Unavailable")
            viewModel.refresh()
            advanceUntilIdle()

            // A failed re-list must not blank a screen that already has rows.
            assertEquals(
                listOf(1L, 2L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
            assertEquals("Server returned 503", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun restore_success_dropsThatRowAndConfirms() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one"), Row(2, "two"), Row(3, "three")))

            viewModel.restore(2)
            advanceUntilIdle()

            assertEquals(listOf(2L), viewModel.restored)
            assertEquals(
                listOf(1L, 3L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
            assertEquals("Row restored", viewModel.uiState.value.statusMessage)
            // Optimistic, not a re-list: the server already said the row is gone.
            assertEquals(1, viewModel.loadCalls)
        }

    @Test
    fun restore_failure_keepsTheRowAndReports() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one"), Row(2, "two")))
            viewModel.restoreResult = ApiResult.Unauthorised

            viewModel.restore(2)
            advanceUntilIdle()

            assertEquals(
                listOf(1L, 2L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
            assertEquals("Session expired — please log in again.", viewModel.uiState.value.statusMessage)
        }

    @Test
    fun purge_success_dropsThatRowAndConfirms() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one"), Row(2, "two")))

            viewModel.purge(1)
            advanceUntilIdle()

            assertEquals(listOf(1L), viewModel.purged)
            assertEquals(
                listOf(2L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
            assertEquals("Row permanently deleted", viewModel.uiState.value.statusMessage)
        }

    @Test
    fun purge_failure_keepsTheRowAndReports() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one")))
            viewModel.purgeResult = ApiResult.NetworkError(IOException("offline"))

            viewModel.purge(1)
            advanceUntilIdle()

            assertEquals(
                listOf(1L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
            assertEquals(
                "Couldn't reach the server. Check your connection.",
                viewModel.uiState.value.statusMessage,
            )
        }

    @Test
    fun purge_unknownId_leavesTheSnapshotAlone() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one")))

            viewModel.purge(99)
            advanceUntilIdle()

            assertEquals(
                listOf(1L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
        }

    @Test
    fun dismissStatus_clearsOnlyTheMessage() =
        runTest(dispatcher) {
            val viewModel = loaded(listOf(Row(1, "one"), Row(2, "two")))
            viewModel.restore(1)
            advanceUntilIdle()

            viewModel.dismissStatus()

            assertNull(viewModel.uiState.value.statusMessage)
            assertEquals(
                listOf(2L),
                viewModel.uiState.value.items
                    .map { it.id },
            )
        }

    /** A ViewModel that has already listed [rows], as its `init` would. */
    private fun TestScope.loaded(rows: List<Row>): FakeRemoteList {
        val viewModel = FakeRemoteList()
        viewModel.loadResult = ApiResult.Success(rows)
        viewModel.refresh()
        advanceUntilIdle()
        return viewModel
    }
}
