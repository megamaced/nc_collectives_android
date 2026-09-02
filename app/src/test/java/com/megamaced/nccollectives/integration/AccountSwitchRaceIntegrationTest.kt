package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #20: a response that was already on the wire when the account
 * changed must not be written into the incoming account's cache.
 *
 * The guard is `AccountGeneration`, and its correctness argument is about
 * *transaction ordering* — the generation is captured before the request goes
 * out and re-read inside the transaction that writes the reply, so Room's
 * serialisation of transactions is what makes it airtight rather than merely
 * narrow. `AccountGenerationTest` can pin the counter's arithmetic; only a
 * real database and a real in-flight request can show that the guard is in
 * the right place.
 *
 * `PageEntity` keys on the raw *server* id, which is what makes this worth a
 * test of its own: two Nextclouds will both happily have a page 17, so a
 * leaked write is not a stale row, it is one account's content presented as
 * another's.
 */
@RunWith(AndroidJUnit4::class)
class AccountSwitchRaceIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun refreshWhoseResponseLandsAfterAWipe_writesNothing() =
        runTest {
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(
                        OcsResponses.page(id = 17, title = "Account A's page"),
                        OcsResponses.page(id = 18, title = "Another of A's"),
                    ),
                    method = "GET",
                )
                // The switch lands while the page list is still in flight — the
                // exact window `cancelUniqueWork` cannot close, because it is
                // asynchronous and `recordPutOutcome` runs `NonCancellable`.
                .whileInFlight("/pages") { env.accountGeneration.invalidate() }

            val result = env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            // The *call* succeeded — the server answered. What must not happen is
            // the commit.
            assertTrue("the request itself should succeed, was $result", result is ApiResult.Success)
            assertNull("page 17 belongs to the outgoing account", env.db.pageDao().getById(17))
            assertNull(env.db.pageDao().getById(18))
            assertTrue(
                "no page rows at all should have been written",
                env.db
                    .pageDao()
                    .idsForCollective(IntegrationEnvironment.COLLECTIVE_ID)
                    .isEmpty(),
            )
        }

    @Test
    fun refreshWithNoSwitch_commitsNormally() =
        runTest {
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 17, title = "Kept")), method = "GET")

            val result = env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertTrue(result is ApiResult.Success)
            assertEquals(
                "Kept",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.title,
            )
        }

    @Test
    fun aWipeBeforeTheRequestGoesOut_doesNotBlockTheNextAccountsRefresh() =
        runTest {
            // The generation is captured per call, so a switch that has already
            // happened must not poison refreshes issued afterwards — otherwise
            // signing into the second account would show an empty app.
            env.accountGeneration.invalidate()
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 17, title = "Account B's page")), method = "GET")

            env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertNotNull(env.db.pageDao().getById(17))
            assertEquals(
                "Account B's page",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.title,
            )
        }

    @Test
    fun aWipeMidRefresh_doesNotDeleteTheIncomingAccountsRows() =
        runTest {
            // The abandoned transaction also carries the reconciliation delete.
            // Bailing out has to skip that too, or a switch landing mid-refresh
            // would take the *new* account's rows with it.
            env.seedPage(id = 17, title = "Already here")
            dispatcher
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "Different page")), method = "GET")
                .whileInFlight("/pages") { env.accountGeneration.invalidate() }

            env.pageRepository.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertNotNull("the existing row must not be reconciled away", env.db.pageDao().getById(17))
            assertNull("and the abandoned response must not be inserted", env.db.pageDao().getById(99))
        }
}
