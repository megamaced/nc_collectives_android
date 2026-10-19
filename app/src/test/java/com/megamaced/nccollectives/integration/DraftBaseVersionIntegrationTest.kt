package com.megamaced.nccollectives.integration

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.domain.model.DraftBase
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.page.PageEditViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #57: text written from one version of a page is saved against that
 * version, even after the cache has moved on to a newer one — as it does
 * when an editor's draft outlives a process restart and the page is
 * revalidated on the way back in, or when the queued edit an editor was
 * filled with is sent and someone else's edit overtakes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DraftBaseVersionIntegrationTest {
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
        Dispatchers.resetMain()
    }

    @Test
    fun aDraftWrittenFromAnOlderVersionIsSentAgainstIt_andKeptWhenTheServerHasMovedOn() =
        runTest {
            env.seedPage(id = 12, bodyMd = "B's text", bodyEtag = "B")
            dispatcher
                .on(".md", OcsResponses.webDav(412), method = "PUT")
                .on(".md", OcsResponses.webDav(200, etag = "\"B\"").setBody("B's text"), method = "GET")

            val outcome = env.pageRepository.saveBody(12, "draft from A", basedOn = DraftBase.of("A's text", "A"))

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals("\"A\"", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
            val row = env.db.pageDao().getById(12)
            assertEquals("the draft is kept beside the newer version", "draft from A", row?.draftBodyMd)
            assertEquals("B's text", row?.bodyMd)
        }

    @Test
    fun anOfflineSaveQueuesTheVersionItWasWrittenFrom() =
        runTest {
            env.seedPage(id = 12, bodyMd = "B's text", bodyEtag = "B")
            // Genuinely offline: nothing listening, as in
            // AttachmentCollisionIntegrationTest.
            env.server.shutdown()

            val outcome = env.pageRepository.saveBody(12, "draft from A", basedOn = DraftBase.of("A's text", "A"))

            assertEquals(SaveOutcome.Queued, outcome)
            assertEquals(
                "A",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.baseEtag,
            )
        }

    @Test
    fun withoutAKnownBaseTheCachedVersionIsUsed() =
        runTest {
            env.seedPage(id = 12, bodyMd = "B's text", bodyEtag = "B")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"C\""), method = "PUT")

            assertEquals(SaveOutcome.Saved, env.pageRepository.saveBody(12, "edited B"))

            assertEquals("\"B\"", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
        }

    /** The text is what decides: the same text under another validator is the same base. */
    @Test
    fun aCacheStillHoldingTheDraftsTextIsItsBase_whateverItsValidator() =
        runTest {
            env.seedPage(id = 12, bodyMd = "A's text", bodyEtag = "A2")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"C\""), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "edited A", basedOn = DraftBase.of("A's text", "A"))

            assertEquals(SaveOutcome.Saved, outcome)
            assertEquals("\"A2\"", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
        }

    /** Filled with a queued edit that has since been sent: the version it became is the base. */
    @Test
    fun aDraftFromAQueuedEditThatLanded_isSavedAgainstTheVersionItBecame() =
        runTest {
            env.seedPage(id = 12, bodyMd = "Queued A", bodyEtag = "C")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"D\""), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "Queued A, edited", basedOn = DraftBase.of("Queued A", null))

            assertEquals(SaveOutcome.Saved, outcome)
            assertEquals("\"C\"", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
        }

    /**
     * Filled with a queued edit, which was sent and then overtaken by someone
     * else's. No version holds the text the draft started from, and the
     * cache's must not stand in: the draft is kept, nothing is written.
     */
    @Test
    fun aDraftFromAQueuedEditThatWasOvertaken_isKeptAndNotWritten() =
        runTest {
            env.seedPage(id = 12, bodyMd = "B, someone else's", bodyEtag = "B")
            dispatcher.on(".md", OcsResponses.webDav(200, etag = "\"B\"").setBody("B, someone else's"), method = "GET")

            val outcome = env.pageRepository.saveBody(12, "Queued A, edited", basedOn = DraftBase.of("Queued A", null))

            assertEquals(SaveOutcome.Conflict, outcome)
            assertTrue(dispatcher.requestsWithMethod("PUT").isEmpty())
            val row = env.db.pageDao().getById(12)
            assertEquals("Queued A, edited", row?.draftBodyMd)
            assertEquals("B, someone else's", row?.bodyMd)
            assertEquals(
                "CONFLICTED",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.status,
            )
        }

    /** Filled with a queued edit that is still waiting: the queue says what it is written against. */
    @Test
    fun aDraftFromAQueuedEditStillWaiting_isWrittenAgainstTheQueuesBase() =
        runTest {
            env.seedPage(id = 12, bodyMd = "Server A", bodyEtag = "A")
            env.db.editQueueDao().upsert(EditQueueEntity(12, "A", "Queued A", 1, "PENDING"))
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"C\""), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "Queued A, edited", basedOn = DraftBase.of("Queued A", null))

            assertEquals(SaveOutcome.Saved, outcome)
            assertEquals("\"A\"", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
        }

    /**
     * The editor end to end: opened on a queued edit, restored after the
     * process died, by which time the edit was sent and someone else's
     * overtook it. The draft must not be written over theirs.
     */
    @Test
    fun anEditorOpenedOnAQueuedEdit_doesNotSaveOverTheVersionThatOvertookIt() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            env.seedPage(id = 12, bodyMd = "Server A", bodyEtag = "A", bodyTimestamp = 100)
            env.db.editQueueDao().upsert(EditQueueEntity(12, "A", "Queued A", 1, "PENDING"))
            dispatcher
                .on(".md", OcsResponses.webDav(304), method = "GET")
                .on(".md", OcsResponses.webDav(204, etag = "\"C\""), method = "PUT")
            val state = SavedStateHandle(mapOf(Destination.PageEdit.ARG_PAGE_ID to 12L))
            val first = PageEditViewModel(state, env.pageRepository, mockk(relaxed = true))
            // Loaded, not just drafted: the state is published after the
            // whole of the seeding, base included, which runs on Room's
            // thread here.
            first.uiState.first { !it.isLoadingBody && it.title == "Page 12" }
            assertEquals("Queued A", first.draftBody.value)
            first.onBodyChanged("Queued A, edited")

            val restoredState = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
            env.db.editQueueDao().deleteForPage(12)
            env.seedPage(id = 12, bodyMd = "B, someone else's", bodyEtag = "B", bodyTimestamp = 100)
            dispatcher.on(".md", OcsResponses.webDav(200, etag = "\"B\"").setBody("B, someone else's"), method = "GET")
            val restored = PageEditViewModel(restoredState, env.pageRepository, mockk(relaxed = true))
            restored.uiState.first { !it.isLoadingBody && it.title == "Page 12" }
            restored.save()
            restored.uiState.first { it.saveSucceeded || it.saveError != null }

            assertTrue("nothing written over B", dispatcher.requestsWithMethod("PUT").isEmpty())
            assertEquals(
                "Queued A, edited",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
        }
}
