package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.AccountBoundCallFactory
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.data.db.entity.BODY_TIMESTAMP_UNAVAILABLE
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.db.entity.PageViewEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.di.NetworkModule
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The data behind Home: who last edited a page, which server version of a
 * body the cache holds, and which version the user has been shown — each
 * written by a different path (page-list refresh, body fetch, page screen)
 * and only meaningful together.
 */
@RunWith(AndroidJUnit4::class)
class HomeFeedDataIntegrationTest {
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

    private val repo get() = env.pageRepository
    private val generation get() = env.accountGeneration.current()

    private fun webDavBody(
        body: String,
        etag: String,
        lastModifiedSeconds: Long?,
    ): MockResponse =
        MockResponse().setResponseCode(200).setBody(body).setHeader("ETag", "\"$etag\"").apply {
            if (lastModifiedSeconds != null) setHeader("Last-Modified", httpDate(lastModifiedSeconds))
        }

    private fun listing(vararg pages: String) {
        dispatcher
            .on("/tags", OcsResponses.emptyTagList(), method = "GET")
            .on("/pages", OcsResponses.pageList(*pages), method = "GET")
    }

    // --- The page list ---------------------------------------------------

    @Test
    fun aRefreshStoresTheLastEditor_andKeepsTheCachedBodysVersion() =
        runTest {
            env.seedPage(id = 41, bodyMd = "# b", bodyEtag = "e1", bodyTimestamp = 1_700_000_000)
            listing(OcsResponses.page(id = 41, timestamp = 1_700_000_100, lastUserId = "bob"))

            repo.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            val row = env.db.pageDao().getById(41)!!
            assertEquals("bob", row.lastUserId)
            assertEquals(1_700_000_100L, row.serverTimestamp)
            assertEquals(1_700_000_000L, row.bodyTimestamp)
        }

    // --- Body versions ----------------------------------------------------

    @Test
    fun aWholeFetchStoresTheBodysLastModified() =
        runTest {
            env.seedPage(id = 41, fileName = "p41.md")
            dispatcher.on("p41.md", webDavBody("# hi", "e2", lastModifiedSeconds = 1_700_000_200), method = "GET")

            repo.refreshBodyIfChanged(41)

            assertEquals(
                1_700_000_200L,
                env.db
                    .pageDao()
                    .getById(41)!!
                    .bodyTimestamp,
            )
        }

    @Test
    fun aNotModifiedKeepsTheStoredVersion() =
        runTest {
            // A 304 carries no Last-Modified: the version is the one fetched before.
            env.seedPage(id = 41, fileName = "p41.md", bodyMd = "# b", bodyEtag = "e1", bodyTimestamp = 1_700_000_000)
            dispatcher.on("p41.md", OcsResponses.webDav(304, etag = "\"e1\""), method = "GET")

            repo.refreshBodyIfChanged(41)

            assertEquals(
                1_700_000_000L,
                env.db
                    .pageDao()
                    .getById(41)!!
                    .bodyTimestamp,
            )
        }

    @Test
    fun aNewBodyWithoutLastModifiedReplacesTheOldVersionWithUnavailable() =
        runTest {
            env.seedPage(id = 41, fileName = "p41.md", bodyMd = "# b", bodyEtag = "e1", bodyTimestamp = 1_700_000_000)
            dispatcher.on("p41.md", webDavBody("# new", "e3", lastModifiedSeconds = null), method = "GET")

            repo.refreshBodyIfChanged(41)

            val row = env.db.pageDao().getById(41)!!
            assertEquals("# new", row.bodyMd)
            assertEquals(BODY_TIMESTAMP_UNAVAILABLE, row.bodyTimestamp)
        }

    @Test
    fun aBodyOfUnknownVersionIsFetchedWholeOnce() =
        runTest {
            // A body cached before versions were stored: no If-None-Match, so
            // the reply is a 200 that says which version it is.
            env.seedPage(id = 41, fileName = "p41.md", bodyMd = "# b", bodyEtag = "e1", bodyTimestamp = null)
            dispatcher.on("p41.md", webDavBody("# b", "e1", lastModifiedSeconds = 1_700_000_300), method = "GET")

            repo.refreshBodyIfChanged(41)

            assertNull(dispatcher.requestsTo("p41.md").single().getHeader("If-None-Match"))
            assertEquals(
                1_700_000_300L,
                env.db
                    .pageDao()
                    .getById(41)!!
                    .bodyTimestamp,
            )
        }

    @Test
    fun theUsersOwnSaveLeavesTheVersionUnknown() =
        runTest {
            env.seedPage(id = 41, fileName = "p41.md", bodyMd = "# b", bodyEtag = "e1", bodyTimestamp = 1_700_000_000)
            dispatcher.on("p41.md", OcsResponses.webDav(204, etag = "\"e4\""), method = "PUT")

            repo.saveBody(41, "# mine")

            val row = env.db.pageDao().getById(41)!!
            assertEquals("# mine", row.bodyMd)
            assertNull(row.bodyTimestamp)
        }

    // --- Recording views --------------------------------------------------

    @Test
    fun theSeenVersionOnlyMovesForward() =
        runTest {
            env.seedPage(id = 41)

            repo.recordView(41, shownVersion = 200, generation = generation)
            repo.recordView(41, shownVersion = 100, generation = generation)
            assertEquals(
                200L,
                env.db
                    .pageViewDao()
                    .get(41)!!
                    .seenTimestamp,
            )

            // The user's own queued text, or a body of unknown version.
            repo.recordView(41, shownVersion = null, generation = generation)
            assertEquals(
                200L,
                env.db
                    .pageViewDao()
                    .get(41)!!
                    .seenTimestamp,
            )
        }

    @Test
    fun aViewFromBeforeAnAccountChangeIsNotRecorded() =
        runTest {
            env.seedPage(id = 41)
            val openedUnder = generation
            env.accountGeneration.invalidate()

            repo.recordView(41, shownVersion = 100, generation = openedUnder)

            assertNull(env.db.pageViewDao().get(41))
        }

    @Test
    fun aViewOfAPageNoLongerCachedIsNotRecorded() =
        runTest {
            repo.recordView(41, shownVersion = 100, generation = generation)

            assertNull(env.db.pageViewDao().get(41))
        }

    // --- Home's lists ------------------------------------------------------

    @Test
    fun aPageChangedBySomeoneElseSinceItWasReadIsUpdated_butNotByTheUser() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 41, serverTimestamp = 100, lastUserId = "bob")
            env.seedPage(id = 42, serverTimestamp = 100, lastUserId = "bob")
            repo.recordView(41, shownVersion = 100, generation = generation)
            repo.recordView(42, shownVersion = 100, generation = generation)
            assertTrue(repo.observeUpdatedSinceViewed("me", 10).first().isEmpty())

            listing(
                OcsResponses.page(id = 41, timestamp = 200, lastUserId = "bob"),
                OcsResponses.page(id = 42, timestamp = 200, lastUserId = "me"),
            )
            repo.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertEquals(listOf(41L), repo.observeUpdatedSinceViewed("me", 10).first().map { it.id })
        }

    @Test
    fun aPageWhoseShownVersionWasNeverKnownIsNeverUpdated() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 41, serverTimestamp = 200, lastUserId = "bob")
            repo.recordView(41, shownVersion = null, generation = generation)

            assertTrue(repo.observeUpdatedSinceViewed("me", 10).first().isEmpty())
            assertEquals(listOf(41L), repo.observeRecentlyViewed(10).first().map { it.id })
        }

    @Test
    fun theUsersRecentEditsAreThePagesTheyLastEdited() =
        runTest {
            env.seedCollective()
            env.seedPage(id = 41, serverTimestamp = 100, lastUserId = "me")
            env.seedPage(id = 42, serverTimestamp = 300, lastUserId = "me")
            env.seedPage(id = 43, serverTimestamp = 200, lastUserId = "bob")

            assertEquals(listOf(42L, 41L), repo.observeLastEditedBy("me", 10).first().map { it.id })
        }

    @Test
    fun needsAttentionListsOnlyLivePagesInLiveCollectives() =
        runTest {
            env.seedCollective()
            env.seedCollective(id = TRASHED_COLLECTIVE)
            env.db.collectiveDao().upsert(
                env.db
                    .collectiveDao()
                    .getById(TRASHED_COLLECTIVE)!!
                    .copy(trashTimestamp = 1),
            )
            // Per kind: a live page, a trashed page, a page in a trashed
            // collective, and a page in a collective no longer cached.
            for ((offset, collectiveId) in listOf(COLLECTIVE, COLLECTIVE, TRASHED_COLLECTIVE, GONE_COLLECTIVE).withIndex()) {
                val draft = env.seedPage(id = 1L + offset, collectiveId = collectiveId, draftBodyMd = "draft")
                val queued = env.seedPage(id = 11L + offset, collectiveId = collectiveId)
                if (offset == 1) {
                    env.db.pageDao().upsertAll(listOf(draft.copy(trashTimestamp = 1), queued.copy(trashTimestamp = 1)))
                }
                env.db.editQueueDao().upsert(EditQueueEntity(queued.id, "base", "pending", queued.id, "PENDING"))
            }

            val attention = repo.observeAttention().first()

            assertEquals(listOf(1L), attention.conflicts.map { it.id })
            assertEquals(listOf(11L), attention.queued.map { it.id })
        }

    @Test
    fun aTagMatchesOnlyItself_wildcardsQuotesAndCaseIncluded() =
        runTest {
            env.seedCollective()
            val tags = listOf("%", "_", "\\", "x%_\\y", "apostrophe's", "Abc", "abc", "plain", "plain-more")
            tags.forEachIndexed { index, tag -> env.seedPage(id = index + 1L, tagsCsv = joinTags(listOf(tag))) }

            tags.forEachIndexed { index, tag ->
                assertEquals(tag, listOf(index + 1L), repo.observePagesWithTag(tag).first().map { it.id })
            }
            assertTrue(repo.observePagesWithTag("missing").first().isEmpty())
        }

    // --- Keeping the history in step with the cache ------------------------

    @Test
    fun aPageThatLeavesTheCacheTakesItsViewWithIt() =
        runTest {
            env.seedPage(id = 41)
            env.seedPage(id = 42)
            repo.recordView(41, shownVersion = 100, generation = generation)
            listing(OcsResponses.page(id = 42))

            repo.refresh(IntegrationEnvironment.COLLECTIVE_ID)

            assertNull(env.db.pageViewDao().get(41))
        }

    @Test
    fun aReissuedIdCarriesTheView_evenOverAStaleRowAtTheNewId() =
        runTest {
            env.seedPage(id = 41, title = "Old")
            repo.recordView(41, shownVersion = 100, generation = generation)
            // A leftover row already at the id the server is about to reissue.
            env.db.pageViewDao().upsert(PageViewEntity(pageId = 99, lastViewedAt = 1, seenTimestamp = 5))
            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 99, title = "New"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "New")), method = "GET")

            val result = repo.renamePage(pageId = 41, newTitle = "New")

            assertTrue("rename should succeed, was $result", result is ApiResult.Success)
            assertNull(env.db.pageViewDao().get(41))
            assertEquals(
                100L,
                env.db
                    .pageViewDao()
                    .get(99)!!
                    .seenTimestamp,
            )
        }

    // --- Identity ------------------------------------------------------------

    private fun currentUser(prefs: UserPreferences): CurrentUser {
        val retrofit = NetworkModule.provideRetrofit(
            AccountBoundCallFactory(env.client, env.accountGeneration),
            NetworkModule.provideJson(),
        )
        return CurrentUser(NetworkModule.provideUserApi(retrofit), prefs, env.accountGeneration)
    }

    @Test
    fun theUsersServerIdIsLookedUpAndKept() =
        runTest {
            val prefs = UserPreferences(env.context).also { it.clearAll() }
            dispatcher.on("/cloud/user", OcsResponses.envelope("""{"id":"alice@example.com"}"""), method = "GET")

            currentUser(prefs).refreshIfUnknown()

            assertEquals("alice@example.com", prefs.currentUserId.first())
        }

    @Test
    fun anIdArrivingAfterAnAccountChangeIsDropped() =
        runTest {
            val prefs = UserPreferences(env.context).also { it.clearAll() }
            dispatcher
                .on("/cloud/user", OcsResponses.envelope("""{"id":"alice@example.com"}"""), method = "GET")
                .whileInFlight("/cloud/user") { env.accountGeneration.invalidate() }

            currentUser(prefs).refreshIfUnknown()

            assertNull(prefs.currentUserId.first())
        }

    @Test
    fun aLookupStartedBeforeAnAccountChangeIsDropped_evenIfTheChangeLandsBeforeItsRequest() =
        runTest {
            val realPrefs = UserPreferences(env.context).also { it.clearAll() }
            val readStarted = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            // Hold the lookup inside its first preference read.
            val prefs = spyk(realPrefs)
            every { prefs.currentUserId } returns flow {
                readStarted.complete(Unit)
                finishRead.await()
                emitAll(realPrefs.currentUserId)
            }
            dispatcher.on("/cloud/user", OcsResponses.envelope("""{"id":"outgoing"}"""), method = "GET")

            val lookup = async(start = CoroutineStart.UNDISPATCHED) { currentUser(prefs).refreshIfUnknown() }
            readStarted.await()
            // A switch, as LocalDataWiper runs it: invalidate, then clear.
            env.accountGeneration.invalidate()
            realPrefs.clearAccountScoped()
            finishRead.complete(Unit)
            lookup.await()

            assertNull(realPrefs.currentUserId.first())
        }

    @Test
    fun theUsersIdIsClearedByAnAccountSwitchAndBySignOut() =
        runTest {
            val prefs = UserPreferences(env.context).also { it.clearAll() }

            prefs.setCurrentUserId("outgoing") { true }
            prefs.clearAccountScoped()
            assertNull(prefs.currentUserId.first())

            prefs.setCurrentUserId("outgoing") { true }
            prefs.clearAll()
            assertNull(prefs.currentUserId.first())
        }

    private companion object {
        const val COLLECTIVE = IntegrationEnvironment.COLLECTIVE_ID
        const val TRASHED_COLLECTIVE = 2L
        const val GONE_COLLECTIVE = 99L

        fun httpDate(epochSeconds: Long): String =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .format(Date(epochSeconds * 1_000L))
    }
}
