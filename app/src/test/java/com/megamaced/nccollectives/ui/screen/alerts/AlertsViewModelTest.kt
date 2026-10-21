package com.megamaced.nccollectives.ui.screen.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.Failure
import com.megamaced.nccollectives.data.auth.AccountBinding
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.AlertKind
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.DismissAllResult
import com.megamaced.nccollectives.domain.model.NotifyLevel
import com.megamaced.nccollectives.domain.model.PageOrder
import com.megamaced.nccollectives.domain.repository.AlertRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.AlertBadgeViewModel
import com.megamaced.nccollectives.ui.navigation.badgeText
import com.megamaced.nccollectives.util.FixtureResources
import com.megamaced.nccollectives.util.english
import com.megamaced.nccollectives.util.resolve
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

internal fun alert(
    id: Long,
    kind: AlertKind = AlertKind.PageUpdated,
    seen: Boolean = false,
    collectiveId: Long? = 7,
    subject: String = "Alert $id",
) = Alert(
    id = id,
    kind = kind,
    pageId = id * 10,
    collectiveId = collectiveId,
    actorName = "Bob",
    pageTitle = "Page $id",
    collectiveName = "Wiki",
    subject = subject,
    timestamp = 0,
    seen = seen,
)

private fun collective(
    id: Long,
    notifyLevel: NotifyLevel?,
) = Collective(
    id = id,
    name = "Collective $id",
    slug = null,
    emoji = null,
    circleId = null,
    canEdit = true,
    canShare = true,
    level = 0,
    userShowMembers = true,
    pageOrder = PageOrder.Custom,
    notifyLevel = notifyLevel,
    isPageShare = false,
    trashed = false,
    favoritePageIds = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlertsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val alerts = MutableStateFlow(listOf(alert(1), alert(2, seen = true), alert(3)))
    private val alertRepository = mockk<AlertRepository>(relaxed = true) {
        every { observeAlerts() } returns alerts
        every { observeSupported() } returns MutableStateFlow(true)
        every { observeUnseenCount() } returns MutableStateFlow(2)
        coEvery { refresh() } returns ApiResult.Success(Unit)
    }
    private val collectiveRepository = mockk<CollectiveRepository>(relaxed = true) {
        every { observeCollectives() } returns
            MutableStateFlow(listOf(collective(1, NotifyLevel.Mentions), collective(2, notifyLevel = null)))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val generation = AccountGeneration()
    private val pageRepository = mockk<PageRepository>(relaxed = true)

    private fun viewModel() = AlertsViewModel(alertRepository, collectiveRepository, pageRepository, generation)

    @Test
    fun showingTheListMarksOnlyTheUnseenSeen_andKeepsThemNewForTheVisit() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.onShown(alerts.value)
            advanceUntilIdle()

            coVerify { alertRepository.markSeen(listOf(1L, 3L), generation.current()) }
            assertEquals(setOf(1L, 3L), vm.newIds.value)

            // Shown again once the cache says they're seen: nothing more to mark.
            vm.onShown(alerts.value.map { it.copy(seen = true) })
            advanceUntilIdle()
            coVerify(exactly = 1) { alertRepository.markSeen(any(), any()) }
            assertEquals(setOf(1L, 3L), vm.newIds.value)
        }

    @Test
    fun whatTheScreenStartsRunsOnTheAccountItWasOpenedUnder() =
        runTest(dispatcher) {
            val opened = generation.current()
            val vm = viewModel()
            generation.invalidate()
            generation.settle()
            val boundTo = mutableListOf<Long?>()
            coEvery { alertRepository.dismiss(any()) } coAnswers {
                boundTo += currentCoroutineContext()[AccountBinding]?.generation
                ApiResult.Success(Unit)
            }
            coEvery { collectiveRepository.setNotifyLevel(any(), any()) } coAnswers {
                boundTo += currentCoroutineContext()[AccountBinding]?.generation
                ApiResult.Success(Unit)
            }

            vm.dismiss(1)
            vm.setNotifyLevel(1, NotifyLevel.Mentions)
            advanceUntilIdle()

            assertEquals(listOf(opened, opened), boundTo)
        }

    @Test
    fun aDismissedAlertLeavesAtOnce_andComesBackIfTheServerRefuses() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<ApiResult<Unit>>()
            coEvery { alertRepository.dismiss(1) } coAnswers { answer.await() }
            val vm = viewModel()
            backgroundScope.launch { vm.alerts.collect {} }
            advanceUntilIdle()

            vm.dismiss(1)
            advanceUntilIdle()
            assertEquals(listOf(2L, 3L), vm.alerts.value!!.map { it.id })

            answer.complete(ApiResult.HttpError(500, "Server error"))
            advanceUntilIdle()
            assertEquals(listOf(1L, 2L, 3L), vm.alerts.value!!.map { it.id })
            assertEquals(Failure.Http(500).toUiText(), vm.messages.first())
        }

    @Test
    fun dismissingAllSaysHowItWent() =
        runTest(dispatcher) {
            val vm = viewModel()
            val said = mutableListOf<String>()

            for (result in listOf(DismissAllResult(3, 0), DismissAllResult(1, 0), DismissAllResult(2, 1), DismissAllResult(0, 2))) {
                coEvery { alertRepository.dismissAll() } returns result
                vm.dismissAll()
                said += vm.messages.first().english()
            }

            assertEquals(
                listOf(
                    "Dismissed 3 alerts",
                    "Dismissed 1 alert",
                    "Dismissed 2 alerts; 1 couldn't be dismissed",
                    "Couldn't dismiss the alerts",
                ),
                said,
            )
        }

    @Test
    fun comingBackToTheTabRefreshes_butNotTwiceInQuickSuccession() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.onResume(now = 100_000)
            vm.onResume(now = 105_000)
            advanceUntilIdle()
            coVerify(exactly = 1) { alertRepository.refresh() }

            vm.onResume(now = 200_000)
            advanceUntilIdle()
            coVerify(exactly = 2) { alertRepository.refresh() }
        }

    @Test
    fun onlyCollectivesThatReportALevelAreOffered() =
        runTest(dispatcher) {
            val vm = viewModel()
            backgroundScope.launch { vm.notifyCollectives.collect {} }
            advanceUntilIdle()

            assertEquals(listOf(1L), vm.notifyCollectives.value.map { it.id })
        }

    @Test
    fun theBadgeRefreshesOnForeground_atMostOnceAMinute() =
        runTest(dispatcher) {
            val badge = AlertBadgeViewModel(alertRepository, generation)

            badge.refreshIfStale(now = 0)
            badge.refreshIfStale(now = 59_000)
            badge.refreshIfStale(now = 61_000)
            advanceUntilIdle()

            coVerify(exactly = 2) { alertRepository.refresh() }
        }

    @Test
    fun theBadgesMinuteIsPerAccount() =
        runTest(dispatcher) {
            val badge = AlertBadgeViewModel(alertRepository, generation)

            badge.refreshIfStale(now = 0)
            generation.invalidate()
            generation.settle()
            badge.refreshIfStale(now = 1_000)
            advanceUntilIdle()

            coVerify(exactly = 2) { alertRepository.refresh() }
        }

    @Test
    fun theBadgeCapsWhatItShows() {
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    @Test
    fun dismissingAllSaysThisAlertForOne_andCountsMore() {
        assertEquals("This alert will be dismissed on the server, for every device you use.", dismissAllConfirmation(1).english())
        assertEquals("These 3 alerts will be dismissed on the server, for every device you use.", dismissAllConfirmation(3).english())
    }

    @Test
    fun alertKindsAndNotifyLevelsHaveTheirOwnWords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(listOf("Mention", "Page changed", "Page deleted"), AlertKind.entries.map { context.getString(it.label()) })
        assertEquals(listOf("Off", "Mentions", "All"), NotifyLevel.entries.map { context.getString(it.label()) })
    }

    @Test
    fun aSubjectlessAlertIsAWholeSentence_withOrWithoutTheTitleAndCollective() {
        fun subject(
            kind: AlertKind,
            title: String?,
            collective: String?,
        ) = fallbackSubject(alert(1, kind = kind).copy(pageTitle = title, collectiveName = collective)).english()

        assertEquals(
            listOf(
                "Bob mentioned you in Plans in Wiki",
                "Bob mentioned you in Plans",
                "Bob mentioned you in a page in Wiki",
                "Bob mentioned you in a page",
                "Bob changed Plans in Wiki",
                "Bob changed Plans",
                "Bob changed a page in Wiki",
                "Bob changed a page",
                "Bob deleted Plans in Wiki",
                "Bob deleted Plans",
                "Bob deleted a page in Wiki",
                "Bob deleted a page",
            ),
            AlertKind.entries.flatMap { kind ->
                listOf(
                    subject(kind, "Plans", "Wiki"),
                    subject(kind, "Plans", null),
                    subject(kind, null, "Wiki"),
                    subject(kind, null, null),
                )
            },
        )
        assertEquals(
            "Someone deleted a page",
            fallbackSubject(
                alert(1, kind = AlertKind.PageDeleted).copy(actorName = null, pageTitle = null, collectiveName = null),
            ).english(),
        )
    }

    @Test
    fun aLanguageCanInflectAnUnnamedPageForEachSentence() {
        // Polish puts "a page" in the locative after "mentioned you in" and
        // in the accusative after "changed" and "deleted".
        val polish = FixtureResources(
            mapOf(
                R.string.alert_mentioned_untitled to "%1\$s wspomniał o tobie na stronie",
                R.string.alert_mentioned_untitled_in to "%1\$s wspomniał o tobie na stronie w %2\$s",
                R.string.alert_changed_untitled to "%1\$s zmienił stronę",
                R.string.alert_deleted_untitled_in to "%1\$s usunął stronę w %2\$s",
            ),
        )

        fun subject(
            kind: AlertKind,
            collective: String?,
        ) = fallbackSubject(alert(1, kind = kind).copy(pageTitle = null, collectiveName = collective)).resolve(polish)

        assertEquals("Bob wspomniał o tobie na stronie", subject(AlertKind.Mention, null))
        assertEquals("Bob wspomniał o tobie na stronie w Wiki", subject(AlertKind.Mention, "Wiki"))
        assertEquals("Bob zmienił stronę", subject(AlertKind.PageUpdated, null))
        assertEquals("Bob usunął stronę w Wiki", subject(AlertKind.PageDeleted, "Wiki"))
    }

    @Test
    fun eachVisitStartsWithNothingNew() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onShown(alerts.value)
            assertEquals(setOf(1L, 3L), vm.newIds.value)

            vm.onResume(now = 1_000_000)

            assertTrue(vm.newIds.value.isEmpty())
        }

    // --- Opening ---------------------------------------------------------------

    private fun page(id: Long) = mockk<com.megamaced.nccollectives.domain.model.Page>()

    @Test
    fun aCachedPageOpensAtOnce() =
        runTest(dispatcher) {
            every { pageRepository.observePage(10) } returns MutableStateFlow(page(10))
            val vm = viewModel()

            vm.open(alert(1), visit = 4)
            advanceUntilIdle()

            assertEquals(AlertTarget.Page(pageId = 10, visit = 4), vm.targets.first())
            coVerify(exactly = 0) { pageRepository.refresh(any()) }
        }

    @Test
    fun anUncachedPageIsFetchedWithItsCollective_thenOpens() =
        runTest(dispatcher) {
            val cached = MutableStateFlow<com.megamaced.nccollectives.domain.model.Page?>(null)
            every { pageRepository.observePage(10) } returns cached
            coEvery { collectiveRepository.cachedCollectives() } returns listOf(collective(7, NotifyLevel.Mentions))
            coEvery { pageRepository.refresh(7) } coAnswers {
                cached.value = page(10)
                ApiResult.Success(Unit)
            }
            val vm = viewModel()

            vm.open(alert(1, collectiveId = 7), visit = 1)
            advanceUntilIdle()

            assertEquals(AlertTarget.Page(pageId = 10, visit = 1), vm.targets.first())
        }

    @Test
    fun anUncachedCollectiveIsFetchedFirst() =
        runTest(dispatcher) {
            val cached = MutableStateFlow<com.megamaced.nccollectives.domain.model.Page?>(null)
            var collectives = emptyList<Collective>()
            every { pageRepository.observePage(10) } returns cached
            coEvery { collectiveRepository.cachedCollectives() } answers { collectives }
            coEvery { collectiveRepository.refresh() } coAnswers {
                collectives = listOf(collective(7, NotifyLevel.Mentions))
                ApiResult.Success(Unit)
            }
            coEvery { pageRepository.refresh(7) } coAnswers {
                cached.value = page(10)
                ApiResult.Success(Unit)
            }
            val vm = viewModel()

            vm.open(alert(1, collectiveId = 7), visit = 1)
            advanceUntilIdle()

            coVerify { collectiveRepository.refresh() }
            assertEquals(AlertTarget.Page(pageId = 10, visit = 1), vm.targets.first())
        }

    @Test
    fun offlineItSaysSo_andOpensNothing() =
        runTest(dispatcher) {
            every { pageRepository.observePage(10) } returns MutableStateFlow(null)
            coEvery { collectiveRepository.cachedCollectives() } returns listOf(collective(7, NotifyLevel.Mentions))
            coEvery { pageRepository.refresh(7) } returns ApiResult.NetworkError(java.io.IOException("offline"))
            val vm = viewModel()

            vm.open(alert(1, collectiveId = 7), visit = 1)
            advanceUntilIdle()

            assertTrue(
                vm.messages
                    .first()
                    .english()
                    .contains("online"),
            )
            assertNull(vm.opening.value)
        }

    @Test
    fun aDeletedPageOpensItsCollective() =
        runTest(dispatcher) {
            coEvery { collectiveRepository.cachedCollectives() } returns listOf(collective(7, NotifyLevel.Mentions))
            val vm = viewModel()

            vm.open(alert(1, kind = AlertKind.PageDeleted, collectiveId = 7), visit = 2)
            advanceUntilIdle()

            assertEquals(AlertTarget.Collective(collectiveId = 7, visit = 2), vm.targets.first())
        }

    @Test
    fun aSecondTapWhileTheFirstIsFetchingIsIgnored() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<ApiResult<Unit>>()
            every { pageRepository.observePage(any()) } returns MutableStateFlow(null)
            coEvery { collectiveRepository.cachedCollectives() } returns listOf(collective(7, NotifyLevel.Mentions))
            coEvery { pageRepository.refresh(7) } coAnswers { answer.await() }
            val vm = viewModel()

            vm.open(alert(1, collectiveId = 7), visit = 1)
            vm.open(alert(3, collectiveId = 7), visit = 1)
            advanceUntilIdle()
            answer.complete(ApiResult.Success(Unit))
            advanceUntilIdle()

            coVerify(exactly = 1) { pageRepository.refresh(7) }
        }
}
