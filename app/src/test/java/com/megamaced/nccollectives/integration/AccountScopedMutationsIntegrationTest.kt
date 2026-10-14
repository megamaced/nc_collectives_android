package com.megamaced.nccollectives.integration

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.api.isAccountChanged
import com.megamaced.nccollectives.data.auth.AccountBinding
import com.megamaced.nccollectives.data.auth.AccountBoundCallFactory
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.di.NetworkModule
import com.megamaced.nccollectives.sync.FullSync
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Operations that read the cache and then send or write what they read stay
 * on the account they started on. Each test lands a whole switch while the
 * operation is under way, gives the incoming account a row under the same
 * id, and checks the outgoing operation leaves that row alone and sends
 * nothing more.
 */
@RunWith(AndroidJUnit4::class)
class AccountScopedMutationsIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private lateinit var collectives: CollectiveRepositoryImpl

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        collectives =
            CollectiveRepositoryImpl(
                api = env.api,
                circlesApi = mockk<CirclesApiService>(),
                dao = env.db.collectiveDao(),
                pageDao = env.db.pageDao(),
                attachmentDao = env.db.attachmentDao(),
                editQueueDao = env.db.editQueueDao(),
                pageViewDao = env.db.pageViewDao(),
                database = env.db,
                accountGeneration = env.accountGeneration,
            )
    }

    @After
    fun tearDown() {
        env.close()
    }

    /** A whole switch, with [incoming] writing the next account's cache in the middle of it. */
    private fun switchAccount(incoming: suspend () -> Unit = {}) {
        env.accountGeneration.invalidate()
        runBlocking { incoming() }
        env.accountGeneration.settle()
    }

    private fun failure() = MockResponse().setResponseCode(500)

    @Test
    fun aRefusedEmojiChangeDoesNotRollBackTheNextAccountsPage() =
        runTest {
            env.seedPage(id = 1)
            dispatcher
                .on("/pages/1/emoji", failure(), method = "PUT")
                .whileInFlight("/pages/1/emoji") { switchAccount { env.db.pageDao().updateEmoji(1, "B") } }

            val result = env.pageRepository.setEmoji(1, "A")

            assertTrue("was $result", result is ApiResult.HttpError)
            assertEquals(
                "B",
                env.db
                    .pageDao()
                    .getById(1)
                    ?.emoji,
            )
        }

    @Test
    fun aBodyFetchedForOneAccountIsNotWrittenToTheNext() =
        runTest {
            env.seedPage(id = 1)
            dispatcher
                .on("remote.php/dav", MockResponse().setBody("A's body"), method = "GET")
                .whileInFlight("remote.php/dav") {
                    switchAccount { env.db.pageDao().updateBody(1, "B's body", null, 0, 0) }
                }

            val result = env.pageRepository.fetchBody(1)

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertEquals(
                "B's body",
                env.db
                    .pageDao()
                    .getById(1)
                    ?.bodyMd,
            )
        }

    @Test
    fun aCopyMadeForOneAccountIsNotCachedForTheNext() =
        runTest {
            env.seedPage(id = 1)
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 60, title = "Copy"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 60)), method = "GET")
                .whileInFlight("/pages/1") { switchAccount() }

            val result = env.pageRepository.copyPage(IntegrationEnvironment.COLLECTIVE_ID, 1)

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertNull(env.db.pageDao().getById(60))
            assertTrue("no refresh after it", dispatcher.requestsWithMethod("GET").isEmpty())
        }

    @Test
    fun aRenameThatReissuesTheIdIsNotCarriedIntoTheNextAccount() =
        runTest {
            env.seedPage(id = 1)
            env.seedPage(id = 2, parentId = 1, title = "Old")
            dispatcher
                .on("/pages/2", OcsResponses.singlePage(id = 70, title = "New", parentId = 1), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 70)), method = "GET")
                .whileInFlight("/pages/2") { switchAccount() }

            val result = env.pageRepository.renamePage(2, "New")

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertNull(env.db.pageDao().getById(70))
            assertTrue("no refresh after it", dispatcher.requestsWithMethod("GET").isEmpty())
        }

    @Test
    fun aRestoreIsNotFollowedByARefreshOnTheNextAccount() =
        runTest {
            dispatcher
                .on("/pages/trash/5", OcsResponses.singlePage(id = 5), method = "PATCH")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 5)), method = "GET")
                .whileInFlight("/pages/trash/5") { switchAccount() }

            val result = env.pageRepository.restorePage(IntegrationEnvironment.COLLECTIVE_ID, 5)

            assertTrue("reported abandoned, was $result", result.isAccountChanged())
            assertTrue(dispatcher.requestsWithMethod("GET").isEmpty())
            assertNull(env.db.pageDao().getById(5))
        }

    @Test
    fun aRenameWhoseRefreshIsRefusedIsReportedAbandoned() =
        runTest {
            env.seedPage(id = 1)
            env.seedPage(id = 2, parentId = 1, title = "Old")
            dispatcher
                // The id comes back unchanged, so nothing is carried: the
                // refresh after it is what the switch refuses.
                .on("/pages/2", OcsResponses.singlePage(id = 2, title = "New", parentId = 1), method = "PUT")
                .whileInFlight("/pages/2") { switchAccount() }

            val result = env.pageRepository.renamePage(2, "New")

            assertTrue("was $result", result.isAccountChanged())
            assertTrue(dispatcher.requestsWithMethod("GET").isEmpty())
        }

    @Test
    fun aMoveWhoseRefreshIsRefusedIsReportedAbandoned() =
        runTest {
            env.seedPage(id = 1)
            env.seedPage(id = 3, parentId = 1, title = "New parent")
            env.seedPage(id = 2, parentId = 1, title = "Moving")
            dispatcher
                .on("/pages/2", OcsResponses.singlePage(id = 2, title = "Moving", parentId = 3), method = "PUT")
                .whileInFlight("/pages/2") { switchAccount() }

            val result = env.pageRepository.movePage(2, 3)

            assertTrue("was $result", result.isAccountChanged())
            assertTrue(dispatcher.requestsWithMethod("GET").isEmpty())
        }

    @Test
    fun aCopyRecordedJustBeforeASwitchIsReportedAbandoned() =
        runTest {
            env.seedPage(id = 1)
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 60, title = "Copy"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 60)), method = "GET")
                // After the copy is recorded: the refresh that follows is on
                // the wire when the switch lands.
                .whileInFlight("/pages") { request -> if (request.method == "GET") switchAccount() }

            val result = env.pageRepository.copyPage(IntegrationEnvironment.COLLECTIVE_ID, 1)

            assertTrue("was $result", result.isAccountChanged())
        }

    @Test
    fun aCollectiveRestoreIsNotFollowedByARefreshOnTheNextAccount() =
        runTest {
            dispatcher
                .on("/trash/7", OcsResponses.envelope("""{"collective":{"id":7,"name":"Wiki"}}"""), method = "PATCH")
                .whileInFlight("/trash/7") { switchAccount() }

            val result = collectives.restoreTrashedCollective(IntegrationEnvironment.COLLECTIVE_ID)

            assertTrue("was $result", result.isAccountChanged())
            assertTrue(dispatcher.requestsWithMethod("GET").isEmpty())
        }

    @Test
    fun anOldDiscardLeavesTheNextAccountsDraftAndQueuedEdit() =
        runTest {
            val old = env.accountGeneration.current()
            switchAccount {
                env.seedPage(id = 1, draftBodyMd = "B's draft")
                env.db.editQueueDao().upsert(EditQueueEntity(1, "B's etag", "B's unsent text", 1, "CONFLICTED"))
            }

            withContext(AccountBinding(old)) { env.pageRepository.discardDraft(1) }

            assertEquals(
                "B's draft",
                env.db
                    .pageDao()
                    .getById(1)
                    ?.draftBodyMd,
            )
            assertNotNull(env.db.editQueueDao().forPage(1))
        }

    @Test
    fun aRefusedFavouriteDoesNotRollBackTheNextAccountsCollective() =
        runTest {
            env.seedCollective()
            dispatcher
                .on("/userSettings/favoritePages", failure(), method = "PUT")
                .whileInFlight("/userSettings/favoritePages") {
                    switchAccount { env.db.collectiveDao().updateFavoritePagesCsv(IntegrationEnvironment.COLLECTIVE_ID, "9") }
                }

            val result = collectives.toggleFavorite(IntegrationEnvironment.COLLECTIVE_ID, pageId = 1, favorite = true)

            assertTrue("was $result", result is ApiResult.HttpError)
            assertEquals(
                "9",
                env.db
                    .collectiveDao()
                    .getById(IntegrationEnvironment.COLLECTIVE_ID)
                    ?.userFavoritePagesCsv,
            )
        }

    @Test
    fun anAttachmentListFetchedForOneAccountIsNotWrittenToTheNext() =
        runTest {
            env.seedPage(id = 1)
            dispatcher
                .on(
                    "/pages/1/attachments",
                    OcsResponses.envelope("""{"attachments":[{"id":3,"name":"a.png","filesize":1,"mimetype":"image/png"}]}"""),
                    method = "GET",
                ).whileInFlight("/pages/1/attachments") { switchAccount() }

            val result = env.attachmentRepository.refresh(1)

            assertTrue("was $result", result is ApiResult.Unexpected)
            assertTrue(
                env.db
                    .attachmentDao()
                    .listForPage(1)
                    .isEmpty(),
            )
        }

    @Test
    fun anUploadStagedWhileTheAccountChangesIsNotQueuedForTheNext() =
        runTest {
            env.seedPage(id = 1)
            val bytes = "A's photo".toByteArray()
            val source = Uri.parse("content://elsewhere/photo.png")
            val theirs = env.stagedFile(1, "photo.png")
            shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver).registerInputStream(
                source,
                object : InputStream() {
                    private var next = 0

                    override fun read(): Int {
                        // The switch lands while the bytes are being copied,
                        // and the next account stages a file of its own
                        // under the same name.
                        if (next == 0) switchAccount { theirs.apply { parentFile?.mkdirs() }.writeText("B's photo") }
                        return if (next < bytes.size) bytes[next++].toInt() and 0xff else -1
                    }
                },
            )

            val name = env.attachmentRepository.enqueueUpload(1, source, "photo.png", "image/png")

            assertNull(name)
            assertTrue(
                env.db
                    .attachmentDao()
                    .listForPage(1)
                    .isEmpty(),
            )
            assertEquals("the next account's file is left alone", "B's photo", theirs.readText())
            assertEquals("and this one's copy is gone", listOf(theirs.name), theirs.parentFile!!.list()!!.toList())
        }

    @Test
    fun aDownloadOvertakenByASwitchHandsOutNothing() =
        runTest {
            env.seedPage(id = 1)
            dispatcher
                .on("a.png", MockResponse().setBody("A's bytes"), method = "GET")
                .whileInFlight("a.png") { switchAccount() }

            val result = env.attachmentRepository.downloadForViewing(1, "a.png")

            assertTrue("was $result", result.isAccountChanged())
        }

    @Test
    fun anUploadCancelledWhileItsBytesAreCopiedLeavesNoCopyBehind() =
        runTest {
            env.seedPage(id = 1)
            val source = Uri.parse("content://elsewhere/cancelled.png")
            val staging = File(env.context.cacheDir, "attachments-pending")
            val before = staging.list().orEmpty().toSet()
            val copying = CountDownLatch(1)
            val release = CountDownLatch(1)
            val bytes = "A's photo".toByteArray()
            shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver).registerInputStream(
                source,
                object : InputStream() {
                    private var next = 0

                    override fun read(): Int {
                        if (next == 0) {
                            copying.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return if (next < bytes.size) bytes[next++].toInt() and 0xff else -1
                    }
                },
            )
            val upload = launch(Dispatchers.Default) { env.attachmentRepository.enqueueUpload(1, source, "photo.png", "image/png") }
            try {
                assertTrue(copying.await(10, TimeUnit.SECONDS))
                upload.cancel()
            } finally {
                release.countDown()
                upload.join()
            }

            assertEquals(before, staging.list().orEmpty().toSet())
            assertTrue(
                env.db
                    .attachmentDao()
                    .listForPage(1)
                    .isEmpty(),
            )
        }

    @Test
    fun anUploadWhoseCopyFailsLeavesNoCopyBehind() =
        runTest {
            env.seedPage(id = 1)
            val source = Uri.parse("content://elsewhere/unreadable.png")
            val staging = File(env.context.cacheDir, "attachments-pending")
            val before = staging.list().orEmpty().toSet()
            shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver).registerInputStream(
                source,
                object : InputStream() {
                    override fun read(): Int = throw IOException("The source went away mid-copy")
                },
            )

            val name = env.attachmentRepository.enqueueUpload(1, source, "photo.png", "image/png")

            assertNull(name)
            assertEquals(before, staging.list().orEmpty().toSet())
        }

    @Test
    fun aSyncOvertakenByASwitchSendsNothingMore() =
        runTest {
            env.seedCollective()
            dispatcher
                .on(
                    "/collectives",
                    OcsResponses.envelope("""{"collectives":[{"id":${IntegrationEnvironment.COLLECTIVE_ID},"name":"Wiki"}]}"""),
                    method = "GET",
                ).on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 1)), method = "GET")
                .whileInFlight("/collectives") { request ->
                    if (request.path!!.endsWith("/collectives")) switchAccount()
                }
            val prefs = UserPreferences(env.context)
            val userApi = NetworkModule.provideUserApi(
                NetworkModule.provideRetrofit(AccountBoundCallFactory(env.client, env.accountGeneration), NetworkModule.provideJson()),
            )
            val sync =
                FullSync(collectives, env.pageRepository, prefs, CurrentUser(userApi, prefs, env.accountGeneration), env.accountGeneration)

            sync.run()

            assertEquals(
                "only the collective list went out",
                listOf("/collectives"),
                dispatcher.requests.map { it.path!!.substringAfter("v1.0").substringBefore('?') },
            )
            assertEquals("and its outcome is not the next account's to show", 0L, prefs.syncStatus.first().lastFailureAt)
        }

    @Test
    fun aFailedDownloadLeavesTheNextAccountsCopyAlone() =
        runTest {
            env.seedPage(id = 1)
            var theirs: File? = null
            dispatcher
                .on("a.png", MockResponse().setResponseCode(500), method = "GET")
                .whileInFlight("a.png") {
                    switchAccount()
                    theirs = AttachmentRepositoryImpl
                        .viewCacheFileFor(env.context, env.accountGeneration.current(), 1, "a.png")
                        .apply {
                            parentFile?.mkdirs()
                            writeText("B's download")
                        }
                }

            val result = env.attachmentRepository.downloadForViewing(1, "a.png")

            assertTrue("was $result", result is ApiResult.HttpError)
            assertEquals("B's download", theirs?.readText())
        }
}
