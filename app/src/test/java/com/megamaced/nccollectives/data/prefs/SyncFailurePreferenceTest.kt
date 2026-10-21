package com.megamaced.nccollectives.data.prefs

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.AppFailure
import com.megamaced.nccollectives.data.api.Failure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A failed sync is stored as its [Failure], by name, so Settings words it in
 * whatever language the app is in when it shows it.
 */
@RunWith(AndroidJUnit4::class)
class SyncFailurePreferenceTest {
    private val prefs = UserPreferences(ApplicationProvider.getApplicationContext())

    @Before
    fun startClean() = runBlocking { prefs.clearAll() }

    private fun status(): SyncStatus = runBlocking { prefs.syncStatus.first() }

    @Test
    fun aFailureIsStoredAsItself() {
        runBlocking { prefs.recordSyncFailure(1_000L, Failure.App(AppFailure.NotOnDevice)) { true } }

        assertEquals(1_000L, status().lastFailureAt)
        assertEquals(Failure.App(AppFailure.NotOnDevice), status().lastFailure)
    }

    @Test
    fun theLatestFailureReplacesTheOneBefore() {
        runBlocking { prefs.recordSyncFailure(1_000L, Failure.Network) { true } }
        runBlocking { prefs.recordSyncFailure(2_000L, Failure.Http(502)) { true } }

        assertEquals(2_000L, status().lastFailureAt)
        assertEquals(Failure.Http(502), status().lastFailure)
    }

    @Test
    fun aFailureForAnAccountNoLongerCurrentIsNotStored() {
        runBlocking { prefs.recordSyncFailure(1_000L, Failure.Network) { false } }

        assertNull(status().lastFailure)
        assertEquals(0L, status().lastFailureAt)
    }

    @Test
    fun aCleanSyncClearsTheFailure() {
        runBlocking { prefs.recordSyncFailure(1_000L, Failure.Network) { true } }

        runBlocking { prefs.recordSyncSuccess(3_000L) { true } }

        assertEquals(3_000L, status().lastSuccessAt)
        assertNull(status().lastFailure)
        assertEquals(0L, status().lastFailureAt)
    }
}
