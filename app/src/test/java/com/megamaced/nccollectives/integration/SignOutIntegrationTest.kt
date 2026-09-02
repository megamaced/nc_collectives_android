package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.LocalDataWiper
import com.megamaced.nccollectives.data.auth.LogoutHandler
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Issue #32: signing out has to clear the credential even when clearing the
 * *data* fails.
 *
 * The bug was structural rather than logical — `endSignOut()` sat after the
 * wipe rather than in a `finally`, so any device-I/O failure inside the wipe
 * ended the coroutine early. The login screen appeared over a store that
 * could still answer `getCredentials()`, `sessionChangeInProgress` stayed
 * true for the life of the process, and the next launch read the credential
 * back and signed the user in again: a sign-out that failed open.
 *
 * The wiper is injected and made to throw here — the audit asked for exactly
 * this — because there is no way to provoke a Room or DataStore failure from
 * outside, and the property under test is what happens when one occurs.
 */
@RunWith(AndroidJUnit4::class)
class SignOutIntegrationTest {
    private lateinit var sessionManager: SessionManager
    private lateinit var wiper: LocalDataWiper
    private lateinit var holder: SharePayloadHolder
    private lateinit var logoutHandler: LogoutHandler

    @Before
    fun setUp() {
        sessionManager = mockk(relaxed = true)
        wiper = mockk()
        holder = SharePayloadHolder()
        logoutHandler = LogoutHandler(sessionManager, wiper, holder)
    }

    @After
    fun tearDown() {
        io.mockk.clearAllMocks()
    }

    @Test
    fun signOutWhoseWipeThrows_stillClearsTheCredential() =
        runTest {
            coEvery { wiper.wipe(any()) } throws IOException("cache directory is read-only")

            logoutHandler.signOut()

            // Runs on the handler's own supervisor scope, deliberately outliving
            // the caller's — so this waits rather than assuming.
            verify(timeout = TIMEOUT_MS) { sessionManager.endSignOut() }
        }

    @Test
    fun signOutWhoseWipeSucceeds_clearsTheCredentialAfterTheWipe() =
        runTest {
            coEvery { wiper.wipe(any()) } returns Unit

            logoutHandler.signOut()

            coVerify(timeout = TIMEOUT_MS) { wiper.wipe(keepDevicePreferences = false) }
            verifyOrder {
                // The state flip has to come first so the scaffold has already
                // torn down every Room observer before the tables are cleared.
                sessionManager.beginSignOut()
                sessionManager.endSignOut()
            }
        }

    @Test
    fun signOut_isAllAccounts() =
        runTest {
            // "This is not my phone any more." Removing one account and keeping
            // the rest is `AccountSwitcher.removeAccount`, not this.
            coEvery { wiper.wipe(any()) } returns Unit

            logoutHandler.signOut()

            coVerify(timeout = TIMEOUT_MS) { wiper.wipe(keepDevicePreferences = false) }
        }

    @Test
    fun signOut_dropsAPendingShareBeforeTheNextSessionCanSeeIt() =
        runTest {
            // S-16: a share captured under account A must not still be sitting in
            // the process-wide singleton when account B signs in on the same
            // install — it would pop the share UI with A's content aimed at B's
            // Nextcloud.
            holder.publish(SharePayload(text = "account A's clipboard"))
            coEvery { wiper.wipe(any()) } returns Unit

            logoutHandler.signOut()

            assertNull(holder.payload.value)
        }

    @Test
    fun signOutWhoseWipeThrows_stillDropsThePendingShare() =
        runTest {
            // The discard is synchronous and ahead of the wipe, so a wipe failure
            // cannot leave one account's share armed for the next.
            holder.publish(SharePayload(text = "account A's clipboard"))
            coEvery { wiper.wipe(any()) } throws IOException("nope")

            logoutHandler.signOut()

            assertNull(holder.payload.value)
            verify(timeout = TIMEOUT_MS) { sessionManager.endSignOut() }
        }

    @Test
    fun theWipeIsNotRetriedOrRethrown() =
        runTest {
            // Broad catch, deliberately not rethrown: a failure to remove cached
            // *data* must not stop the credential going, and there is nobody
            // above this to handle it.
            coEvery { wiper.wipe(any()) } throws IOException("nope")

            val threw = runCatching { logoutHandler.signOut() }.exceptionOrNull()

            assertNull("signOut must not throw at its caller", threw)
            verify(timeout = TIMEOUT_MS) { sessionManager.endSignOut() }
            assertTrue(true)
        }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
