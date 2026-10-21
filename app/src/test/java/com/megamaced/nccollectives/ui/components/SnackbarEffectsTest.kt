package com.megamaced.nccollectives.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.util.UiText
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * A snackbar message is worded when it is shown, in the language of that
 * moment, even by an effect that started before the language changed.
 * Polish is the test language (see `UiTextTest`).
 */
@RunWith(AndroidJUnit4::class)
class SnackbarEffectsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun resourcesIn(tag: String): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(configuration).resources
    }

    @Test
    fun aMessageAfterTheLanguageChangesIsInTheNewLanguage() {
        val resources = mutableStateOf(resourcesIn("en"))
        val messages = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
        val host = SnackbarHostState()
        rule.setContent {
            CompositionLocalProvider(LocalResources provides resources.value) {
                SnackbarMessageEffect(messages, host)
            }
        }
        rule.runOnIdle { resources.value = resourcesIn("pl") }
        rule.runOnIdle { assertTrue(messages.tryEmit(UiText.Plural(R.plurals.i18n_fixture_files, 3))) }
        rule.waitUntil(5_000) { host.currentSnackbarData != null }
        rule.runOnIdle { assertEquals("3 pliki", host.currentSnackbarData!!.visuals.message) }
    }
}
