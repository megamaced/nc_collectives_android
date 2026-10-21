package com.megamaced.nccollectives.data.prefs

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** How pages open is a device setting: Read mode until changed, kept across an account switch, reset by signing out. */
@RunWith(AndroidJUnit4::class)
class PageOpenModePreferenceTest {
    private val prefs = UserPreferences(ApplicationProvider.getApplicationContext())

    @Before
    fun startClean() = runBlocking { prefs.clearAll() }

    private fun mode(): PageOpenMode = runBlocking { prefs.flow.first().pageOpenMode }

    @Test
    fun pagesOpenForReadingUntilToldOtherwise() {
        assertEquals(PageOpenMode.Read, mode())
    }

    @Test
    fun editModeIsKept() {
        runBlocking { prefs.setPageOpenMode(PageOpenMode.Edit) }

        assertEquals(PageOpenMode.Edit, mode())
    }

    @Test
    fun anAccountSwitchKeepsIt_andSigningOutResetsIt() {
        runBlocking { prefs.setPageOpenMode(PageOpenMode.Edit) }

        runBlocking { prefs.clearAccountScoped() }
        assertEquals(PageOpenMode.Edit, mode())

        runBlocking { prefs.clearAll() }
        assertEquals(PageOpenMode.Read, mode())
    }
}
