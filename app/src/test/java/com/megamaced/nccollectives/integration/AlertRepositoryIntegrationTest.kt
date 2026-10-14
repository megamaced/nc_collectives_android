package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.isAccountChanged
import com.megamaced.nccollectives.domain.model.AlertKind
import com.megamaced.nccollectives.domain.model.DismissAllResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Alerts cache: what a refresh keeps, what the tab shows and counts,
 * and what dismissing touches on the server — one notification at a time,
 * and only ever the ones this app shows.
 */
@RunWith(AndroidJUnit4::class)
class AlertRepositoryIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        kotlinx.coroutines.runBlocking { env.userPreferences.clearAll() }
    }

    @After
    fun tearDown() {
        env.close()
    }

    private val repo get() = env.alertRepository

    private fun updated(
        id: Long,
        pageId: Long = 11,
        datetime: String = "2026-10-14T19:00:00+00:00",
    ) = """{"notification_id":$id,"app":"collectives","datetime":"$datetime","object_type":"page","object_id":"$pageId",
        |"subject":"Bob updated a page","subjectRichParameters":{"user":{"type":"user","id":"bob","name":"Bob"},
        |"collective":{"type":"highlight","id":"7","name":"Wiki"},
        |"page":{"type":"highlight","id":"$pageId","name":"Page","link":"https://x/p"}}}
        """.trimMargin()

    /** A Text mention Collectives didn't vouch for: shown only while its page is cached. */
    private fun bareMention(
        id: Long,
        fileId: Long,
    ) = """{"notification_id":$id,"app":"text","datetime":"2026-10-14T18:00:00+00:00","object_type":"file",
        |"object_id":"$fileId","subject":"Bob mentioned you","subjectRichParameters":{"user":{"type":"user","id":"bob","name":"Bob"}}}
        """.trimMargin()

    private fun otherApp(id: Long) =
        """{"notification_id":$id,"app":"files_sharing","object_type":"share","object_id":"3","subject":"x","subjectRichParameters":[]}"""

    private fun serve(vararg notifications: String) {
        dispatcher.on("/notifications", OcsResponses.envelope("[${notifications.joinToString(",")}]"), method = "GET")
    }

    private fun ok() = OcsResponses.envelope("[]")

    /** The server's capabilities, with the Notifications app offering [endpoints], or no app at all when null. */
    private fun capabilities(vararg endpoints: String?) {
        val notifications = if (endpoints.singleOrNull() == null && endpoints.size == 1) {
            ""
        } else {
            ""","notifications":{"ocs-endpoints":[${endpoints.joinToString(",") { "\"$it\"" }}]}"""
        }
        dispatcher.on(
            "/cloud/capabilities",
            OcsResponses.envelope("""{"version":{"major":34},"capabilities":{"core":{}$notifications}}"""),
            method = "GET",
        )
    }

    // --- Refresh -------------------------------------------------------------

    @Test
    fun aRefreshKeepsCollectivePagesAlerts_newestFirst_andNothingElse() =
        runTest {
            serve(
                updated(1, datetime = "2026-10-14T10:00:00+00:00"),
                otherApp(2),
                updated(3, pageId = 12, datetime = "2026-10-14T12:00:00+00:00"),
            )

            assertEquals(ApiResult.Success(Unit), repo.refresh())

            val alerts = repo.observeAlerts().first()
            assertEquals(listOf(3L, 1L), alerts.map { it.id })
            assertEquals(AlertKind.PageUpdated, alerts.first().kind)
            assertEquals(true, repo.observeSupported().first())
        }

    @Test
    fun whatTheTabHasShownStaysShownAcrossARefresh_andNewArrivalsCount() =
        runTest {
            serve(updated(1))
            repo.refresh()
            assertEquals(1, repo.observeUnseenCount().first())
            repo.markSeen(repo.observeAlerts().first().map { it.id }, env.accountGeneration.current())
            assertEquals(0, repo.observeUnseenCount().first())

            serve(updated(1), updated(2, pageId = 12))
            repo.refresh()

            assertEquals(1, repo.observeUnseenCount().first())
            assertEquals(mapOf(1L to true, 2L to false), repo.observeAlerts().first().associate { it.id to it.seen })
        }

    @Test
    fun alertsTheServerNoLongerListsGo() =
        runTest {
            serve(updated(1), updated(2, pageId = 12))
            repo.refresh()
            serve(updated(2, pageId = 12))
            repo.refresh()
            assertEquals(listOf(2L), repo.observeAlerts().first().map { it.id })

            serve()
            repo.refresh()
            assertTrue(repo.observeAlerts().first().isEmpty())
        }

    @Test
    fun anUnvouchedMentionAppearsOnceItsPageIsCachedInALiveCollective_andCountsOnlyThen() =
        runTest {
            serve(bareMention(5, fileId = 40))
            repo.refresh()
            assertTrue(repo.observeAlerts().first().isEmpty())
            assertEquals(0, repo.observeUnseenCount().first())

            // A cached page in a collective that isn't live still doesn't count,
            // as on Home.
            env.seedPage(id = 40)
            assertTrue(repo.observeAlerts().first().isEmpty())

            env.seedCollective()

            assertEquals(listOf(5L), repo.observeAlerts().first().map { it.id })
            assertEquals(1, repo.observeUnseenCount().first())
        }

    @Test
    fun aServerWithoutNotificationsSaysSo_andClearsWhatWasCached() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher.on("/notifications", MockResponse().setResponseCode(404), method = "GET")
            capabilities(null)

            assertEquals(ApiResult.Success(Unit), repo.refresh())

            assertEquals(false, repo.observeSupported().first())
            assertTrue(repo.observeAlerts().first().isEmpty())
        }

    @Test
    fun aFailedRefreshKeepsTheCache() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher.on("/notifications", MockResponse().setResponseCode(500), method = "GET")

            assertTrue(repo.refresh() is ApiResult.HttpError)
            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun aListLandingAfterAnAccountSwitchWritesNothing_andIsReportedAbandoned() =
        runTest {
            serve(updated(1))
            dispatcher.whileInFlight("/notifications") { env.accountGeneration.invalidate() }

            assertTrue(repo.refresh().isAccountChanged())

            assertTrue(repo.observeAlerts().first().isEmpty())
            assertNull("the outgoing account's answer says nothing of the next one's server", repo.observeSupported().first())
        }

    @Test
    fun aFailureAnsweredAfterAnAccountSwitchIsReportedAbandoned() =
        runTest {
            // Not an error for the account now shown: the one it was for has gone.
            dispatcher
                .on("/notifications", MockResponse().setResponseCode(500), method = "GET")
                .whileInFlight("/notifications") { env.accountGeneration.invalidate() }

            assertTrue(repo.refresh().isAccountChanged())
        }

    @Test
    fun aMissingAppAnsweredAfterAnAccountSwitchClearsNothing_andIsReportedAbandoned() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher
                .on("/notifications", MockResponse().setResponseCode(404), method = "GET")
                // After the capabilities say the app is missing, before the
                // cache is cleared.
                .whileInFlight("/cloud/capabilities") { env.accountGeneration.invalidate() }
            capabilities(null)

            assertTrue(repo.refresh().isAccountChanged())

            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
            assertEquals(true, repo.observeSupported().first())
        }

    // --- Dismissing ------------------------------------------------------------

    @Test
    fun dismissingOneDeletesJustThatNotification() =
        runTest {
            serve(updated(1), updated(2, pageId = 12))
            repo.refresh()
            dispatcher.on("/notifications/1", ok(), method = "DELETE")

            assertEquals(ApiResult.Success(Unit), repo.dismiss(1))

            assertEquals(listOf(2L), repo.observeAlerts().first().map { it.id })
            assertEquals(listOf("/ocs/v2.php/apps/notifications/api/v2/notifications/1"), deletes())
        }

    @Test
    fun aDelete404IsAFailure_whateverTheCapabilitiesSay() =
        runTest {
            // The Notifications app answers 200 for one already dismissed
            // elsewhere, so a 404 is a proxy's or a broken route's.
            serve(updated(1))
            repo.refresh()
            dispatcher.on(
                "/notifications/1",
                MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html>Not Found</html>"),
                method = "DELETE",
            )
            capabilities("list", "delete")

            assertTrue(repo.dismiss(1) is ApiResult.HttpError)
            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun oneTheServerRefusesStays() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher.on("/notifications/1", MockResponse().setResponseCode(500), method = "DELETE")

            assertTrue(repo.dismiss(1) is ApiResult.HttpError)
            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun dismissingAllGoesOneByOne_overWhatIsShown_andKeepsWhatIsRefused() =
        runTest {
            // 5 is a mention of a page not cached, so not shown: dismissing
            // "all" must not reach it.
            serve(updated(1), updated(2, pageId = 12), updated(3, pageId = 13), bareMention(5, fileId = 40))
            repo.refresh()
            dispatcher
                .on("/notifications/", ok(), method = "DELETE")
                .on("/notifications/2", MockResponse().setResponseCode(500), method = "DELETE")

            assertEquals(DismissAllResult(dismissed = 2, failed = 1), repo.dismissAll())

            assertEquals(listOf(2L), repo.observeAlerts().first().map { it.id })
            assertEquals(
                setOf(1L, 2L, 3L),
                deletes().map { it.substringAfterLast('/').toLong() }.toSet(),
            )
        }

    @Test
    fun dismissingAllStopsWhenTheAccountChanges() =
        runTest {
            serve(updated(1), updated(2, pageId = 12))
            repo.refresh()
            dispatcher
                .on("/notifications/", ok(), method = "DELETE")
                .whileInFlight("/notifications/") { request ->
                    if (request.method == "DELETE") env.accountGeneration.invalidate()
                }

            val result = repo.dismissAll()

            // The first went out under the old account; nothing after it may,
            // since the same id names someone else's notification on the
            // next account's server. Neither is reported dismissed: the
            // first's cache write was refused with its account, as every
            // write is once that account has gone.
            assertEquals(1, deletes().size)
            assertEquals(DismissAllResult(dismissed = 0, failed = 2), result)
        }

    @Test
    fun theServersDismissAllIsNeverUsed() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher.on("/notifications/", ok(), method = "DELETE")

            repo.dismissAll()

            assertFalse(deletes().any { it.endsWith("/notifications") })
        }

    private fun deletes(): List<String> = dispatcher.requestsWithMethod("DELETE").mapNotNull { it.path }

    // --- What a 404 or a 204 means -------------------------------------------

    @Test
    fun aBare404WhileTheCapabilitiesListNotifications_isAFailure_andKeepsTheCache() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher.on("/notifications", MockResponse().setResponseCode(404), method = "GET")
            capabilities("list", "delete")

            assertTrue(repo.refresh() is ApiResult.HttpError)

            assertEquals(true, repo.observeSupported().first())
            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun aBare404WhenTheCapabilitiesCannotBeRead_isAFailure_andKeepsTheCache() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher
                .on("/notifications", MockResponse().setResponseCode(404), method = "GET")
                .on("/cloud/capabilities", MockResponse().setResponseCode(502), method = "GET")

            assertTrue(repo.refresh() is ApiResult.HttpError)
            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun aNoContentAnswerMeansNothingToList() =
        runTest {
            serve(updated(1))
            repo.refresh()
            dispatcher.on("/notifications", MockResponse().setResponseCode(204), method = "GET")

            assertEquals(ApiResult.Success(Unit), repo.refresh())

            assertTrue(repo.observeAlerts().first().isEmpty())
            assertEquals(true, repo.observeSupported().first())
        }

    @Test
    fun anEntryThatIsNoObjectIsSkipped_andTheRestKept() =
        runTest {
            dispatcher.on("/notifications", OcsResponses.envelope("[5, null, \"x\", ${updated(1)}]"), method = "GET")

            assertEquals(ApiResult.Success(Unit), repo.refresh())
            assertEquals(listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    // --- Ordering ---------------------------------------------------------------

    @Test
    fun refreshesStartedTogetherShareOneRequest() =
        runTest {
            val release = CompletableDeferred<Unit>()
            serve(updated(1))
            dispatcher.whileInFlight("/notifications") { request ->
                if (request.method == "GET") runBlocking { release.await() }
            }

            val first = async(Dispatchers.IO) { repo.refresh() }
            val second = async(Dispatchers.IO) { repo.refresh() }
            // Let both reach the repository before the server answers.
            Thread.sleep(200)
            release.complete(Unit)

            assertEquals(ApiResult.Success(Unit), first.await())
            assertEquals(ApiResult.Success(Unit), second.await())
            assertEquals(1, dispatcher.requestsTo("/notifications").count { it.method == "GET" })
        }

    @Test
    fun aCallerThatJoinedARefreshRechecksItsAccountWhenItResumes() =
        runTest {
            // The refresh it joined finishes on the outgoing account, and the
            // switch lands before this caller's own continuation runs.
            val requested = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            serve(updated(1))
            dispatcher.whileInFlight("/notifications") { request ->
                if (request.method == "GET") {
                    requested.complete(Unit)
                    runBlocking { release.await() }
                }
            }
            val waiterScheduler = TestCoroutineScheduler()
            val waiterScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(waiterScheduler))
            try {
                val owner = async(Dispatchers.IO) { repo.refresh() }
                requested.await()
                val joined = waiterScope.async { repo.refresh() }
                // Joined the refresh on the wire, and waiting on its answer.
                waiterScheduler.runCurrent()
                release.complete(Unit)
                assertEquals(ApiResult.Success(Unit), owner.await())
                assertFalse("its continuation hasn't run yet", joined.isCompleted)

                env.accountGeneration.invalidate()
                env.accountGeneration.settle()
                waiterScheduler.runCurrent()

                assertTrue(joined.await().isAccountChanged())
                assertEquals(1, dispatcher.requestsTo("/notifications").count { it.method == "GET" })
            } finally {
                release.complete(Unit)
                waiterScope.cancel()
            }
        }

    @Test
    fun aRefreshOnTheNextAccountMakesItsOwnRequest() =
        runTest {
            val release = CompletableDeferred<Unit>()
            serve(updated(1))
            dispatcher.whileInFlight("/notifications") { request ->
                if (request.method == "GET") runBlocking { release.await() }
            }

            val outgoing = async(Dispatchers.IO) { repo.refresh() }
            // The outgoing account's list is on the wire when the switch lands.
            Thread.sleep(200)
            env.accountGeneration.invalidate()
            env.accountGeneration.settle()
            val incoming = async(Dispatchers.IO) { repo.refresh() }
            Thread.sleep(200)
            release.complete(Unit)
            outgoing.await()
            incoming.await()

            assertEquals(2, dispatcher.requestsTo("/notifications").count { it.method == "GET" })
            assertEquals("the incoming account's own list", listOf(1L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun anOlderListCannotBringBackAnAlertDismissedAfterIt() =
        runTest {
            serve(updated(1), updated(2, pageId = 12))
            repo.refresh()
            // The next list is already on its way when the user dismisses 1;
            // the server's answer was taken before the dismissal.
            val listing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            dispatcher
                .on("/notifications/1", ok(), method = "DELETE")
                .whileInFlight("/notifications") { request ->
                    if (request.method == "GET") {
                        listing.complete(Unit)
                        runBlocking { release.await() }
                    }
                }

            val refresh = async(Dispatchers.IO) { repo.refresh() }
            listing.await()
            val dismiss = async(Dispatchers.IO) { repo.dismiss(1) }
            Thread.sleep(200)
            release.complete(Unit)
            refresh.await()
            assertEquals(ApiResult.Success(Unit), dismiss.await())

            assertEquals(listOf(2L), repo.observeAlerts().first().map { it.id })
        }

    @Test
    fun alertsShownBeforeASwitchAreNotMarkedSeenInTheNextAccount() =
        runTest {
            serve(updated(1))
            repo.refresh()
            val openedUnder = env.accountGeneration.current()
            env.accountGeneration.invalidate()

            repo.markSeen(listOf(1L), openedUnder)

            assertEquals(
                false,
                repo
                    .observeAlerts()
                    .first()
                    .single()
                    .seen,
            )
        }
}
