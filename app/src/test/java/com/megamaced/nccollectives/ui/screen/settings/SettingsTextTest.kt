package com.megamaced.nccollectives.ui.screen.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.AccountSummary
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.PageOpenMode
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.TextScale
import com.megamaced.nccollectives.data.prefs.ThemeMode
import com.megamaced.nccollectives.util.english
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The words Settings builds: its options, and what switching or removing an account warns. */
@RunWith(AndroidJUnit4::class)
class SettingsTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ada = AccountSummary(id = "a", host = "cloud.example.com", loginName = "ada")

    @Test
    fun eachOptionHasItsOwnWords() {
        assertEquals(listOf("Follow system", "Light", "Dark"), ThemeMode.entries.map { context.getString(themeModeLabel(it)) })
        assertEquals(listOf("Small", "Default", "Large", "Larger"), TextScale.entries.map { context.getString(textScaleLabel(it)) })
        assertEquals(
            listOf("Off — manual refresh only", "Every hour", "Every 6 hours (default)", "Every 12 hours", "Once a day"),
            SyncCadence.entries.map { context.getString(syncCadenceLabel(it)) },
        )
        assertEquals(
            listOf("Prefer plain markdown (default)", "Prefer collaborative (beta, when online)"),
            EditorPreference.entries.map { context.getString(editorPreferenceLabel(it)) },
        )
        assertEquals(
            listOf("As each collective is set (default)", "Read mode", "Edit mode"),
            PageOpenMode.entries.map { context.getString(pageOpenModeLabel(it)) },
        )
    }

    @Test
    fun switchingWarnsOfUnsyncedEdits_onlyOnceTheirCountIsKnownAndNotZero() {
        val base = "Pages cached on this device will be removed and ada's downloaded instead. Your other account stays signed in."
        assertEquals(base, switchWarning(ada, pendingEdits = null).english())
        assertEquals(base, switchWarning(ada, pendingEdits = 0).english())
        assertEquals("$base One edit hasn't reached the server yet and will be lost.", switchWarning(ada, pendingEdits = 1).english())
        assertEquals("$base 3 edits haven't reached the server yet and will be lost.", switchWarning(ada, pendingEdits = 3).english())
    }

    @Test
    fun removingAnAccountSaysWhatHappensToItAndToTheApp() {
        assertEquals(
            "ada will be signed out on this device. Nothing you have cached is affected — this isn't the account you're using.",
            removalWarning(ada, isActive = false, pendingEdits = 3, accountCount = 2).english(),
        )
        assertEquals(
            "ada will be signed out and their cached pages removed. You'll be switched to your other account.",
            removalWarning(ada, isActive = true, pendingEdits = 0, accountCount = 2).english(),
        )
        assertEquals(
            "ada will be signed out and their cached pages removed. " +
                "This is your last account, so you'll be returned to the login screen. " +
                "One edit hasn't reached the server yet and will be lost.",
            removalWarning(ada, isActive = true, pendingEdits = 1, accountCount = 1).english(),
        )
    }
}
