package com.megamaced.nccollectives.data.auth

import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.sync.SyncScheduler
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Session changes run one at a time, and only the last of a queue lands the
 * session: settles the account generation and publishes the state that lets
 * work start. Real `AccountSwitcher`, `LogoutHandler` and `SessionManager`;
 * the wipe is held open to put changes on top of each other.
 */
class SessionChangeQueueTest {
    private val generation = AccountGeneration()
    private val active = AtomicReference<String?>("A")
    private val stored = Collections.synchronizedList(mutableListOf("A", "B", "C"))
    private val tokenCalls = Collections.synchronizedList(mutableListOf<String>())
    private val tokenStore = mockk<TokenStore>(relaxed = true) {
        every { activeAccountId() } answers { active.get() }
        every { accounts() } answers { stored.toList().map { AccountSummary(it, "https://cloud.example", it) } }
        every { getCredentials() } answers { active.get()?.let { StoredCredentials("https://cloud.example", it, "password") } }
        every { setActiveAccount(any()) } answers {
            active.set(firstArg())
            true
        }
        every { clear() } answers {
            tokenCalls += "clear"
            active.set(null)
        }
        every { upsertAndActivate(any(), any(), any()) } answers {
            tokenCalls += "activate ${secondArg<String>()}"
            active.set(secondArg())
            "https://cloud.example|${secondArg<String>()}"
        }
    }
    private val sessionManager = SessionManager(tokenStore, Lazy { mockk<ExpiredSessionHandler>() }, generation)

    private val wipes = AtomicInteger()
    private val wipeEntered = listOf(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
    private val wipeRelease = listOf(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
    private val wiper = mockk<LocalDataWiper> {
        coEvery { wipe(any()) } coAnswers {
            val index = wipes.getAndIncrement()
            generation.invalidate()
            wipeEntered[index].countDown()
            check(wipeRelease[index].await(10, TimeUnit.SECONDS))
        }
    }
    private val synced = CountDownLatch(1)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true) {
        every { syncNow() } answers { synced.countDown() }
    }
    private val switcher = AccountSwitcher(
        sessionManager,
        tokenStore,
        wiper,
        syncScheduler,
        mockk<SharePayloadHolder>(relaxed = true),
        mockk<NcCollectivesDatabase>(relaxed = true),
    )

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(10, TimeUnit.SECONDS))

    @Test
    fun theFirstOfTwoSwitchesToFinishDoesNotLandTheSession() {
        switcher.switchTo("B")
        await(wipeEntered[0])
        switcher.switchTo("C")

        wipeRelease[0].countDown()
        // B has been activated, and C's wipe begun: still mid-switch.
        await(wipeEntered[1])
        assertEquals("B", active.get())
        assertFalse("generation ${generation.current()} must not be current mid-switch", generation.isCurrent(generation.current()))
        assertEquals(AuthState.Switching, sessionManager.authState.value)

        wipeRelease[1].countDown()
        await(synced)
        assertEquals("C", active.get())
        assertTrue(generation.isCurrent(generation.current()))
        assertEquals(AuthState.Authenticated, sessionManager.authState.value)
    }

    @Test
    fun aSignInDuringASignOutWaitsForIt_andKeepsItsCredential() {
        val logout = LogoutHandler(sessionManager, wiper, mockk<SharePayloadHolder>(relaxed = true))
        logout.signOut()
        await(wipeEntered[0])

        switcher.signInTo("https://cloud.example", "D", "password")
        Thread.sleep(200)
        assertTrue("the sign-in must wait for the sign-out, was $tokenCalls", tokenCalls.isEmpty())

        wipeRelease[0].countDown()
        await(synced)
        assertEquals(listOf("clear", "activate D"), tokenCalls.toList())
        assertEquals("D", active.get())
        assertTrue(generation.isCurrent(generation.current()))
        assertEquals(AuthState.Authenticated, sessionManager.authState.value)
    }

    @Test
    fun aSecondSwitchToTheSameAccountFindsItDone() {
        switcher.switchTo("B")
        await(wipeEntered[0])
        switcher.switchTo("B")

        wipeRelease[0].countDown()
        await(synced)
        Thread.sleep(200)

        assertEquals("one wipe, not two", 1, wipes.get())
        assertEquals("B", active.get())
        assertTrue(generation.isCurrent(generation.current()))
    }

    @Test
    fun aChangeCountedInAScopeAlreadyCancelledStillLetsTheNextLand() {
        val ran = CountDownLatch(1)
        val dead = CoroutineScope(Job().apply { cancel() })
        justRun { tokenStore.removeAccount(any()) }
        sessionManager.launchChange(dead, begin = {}) { ran.countDown() }
        Thread.sleep(200)

        switcher.switchTo("C")
        wipeRelease[0].countDown()
        await(synced)

        assertTrue(generation.isCurrent(generation.current()))
        assertEquals(AuthState.Authenticated, sessionManager.authState.value)
    }

    private val release = CountDownLatch(1)

    /** Two bare changes: the first held open until [release], the second queued behind it in [next]. */
    private fun twoChanges(
        next: CoroutineScope,
        afterFirst: () -> Unit = {},
    ): Pair<Job, Job> {
        val firstJob = SupervisorJob()
        val nextJob = next.coroutineContext[Job]!!
        val entered = CountDownLatch(1)
        sessionManager.launchChange(CoroutineScope(firstJob + Dispatchers.IO), begin = sessionManager::beginAccountSwitch) {
            generation.invalidate()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            sessionManager.endAccountSwitch()
            afterFirst()
        }
        await(entered)
        sessionManager.launchChange(next, begin = sessionManager::beginAccountSwitch) { sessionManager.endAccountSwitch() }
        return firstJob to nextJob
    }

    private fun finish(vararg jobs: Job) {
        release.countDown()
        runBlocking { jobs.flatMap { it.children.toList() }.joinAll() }
    }

    @Test
    fun aChangeResumingInsideItsPredecessorsUnlockDoesNotCountThePredecessor() {
        // Unconfined: the queued change resumes inside the running one's
        // unlock, before anything after the lock has run.
        val (first, next) = twoChanges(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        finish(first, next)

        assertTrue("generation ${generation.current()}", generation.isCurrent(generation.current()))
        assertEquals(AuthState.Authenticated, sessionManager.authState.value)
    }

    @Test
    fun aQueuedChangeCancelledWhileItWaitsStillLandsTheSessionLeftToIt() {
        val next = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // The running change defers landing to the queued one, which is then
        // cancelled before its turn.
        val (first, nextJob) = twoChanges(next, afterFirst = { next.cancel() })

        finish(first, nextJob)

        assertTrue("generation ${generation.current()}", generation.isCurrent(generation.current()))
        assertEquals(AuthState.Authenticated, sessionManager.authState.value)
    }

    @Test
    fun aRemovalQueuedBehindASwitchLandsTheSessionTheSwitchLeftToIt() {
        every { tokenStore.removeAccount(any()) } answers {
            stored.remove(firstArg<String>())
            active.get()
        }
        switcher.switchTo("B")
        await(wipeEntered[0])
        // Not the live account, so no wipe — but it waits its turn.
        switcher.removeAccount("C")

        wipeRelease[0].countDown()
        await(synced)

        assertEquals("B", active.get())
        assertFalse(stored.contains("C"))
        assertTrue(generation.isCurrent(generation.current()))
        assertEquals(AuthState.Authenticated, sessionManager.authState.value)
    }
}
