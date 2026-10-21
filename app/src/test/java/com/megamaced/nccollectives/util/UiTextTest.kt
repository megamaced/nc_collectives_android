package com.megamaced.nccollectives.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Text resolves through the app's resources in the language of the moment.
 * Polish is the test language: it has four plural forms where English has
 * two, and its strings put their arguments in another order. The strings
 * are test fixtures in the debug source set (`i18n_fixture.xml`), so they
 * never reach a release build.
 */
@RunWith(AndroidJUnit4::class)
class UiTextTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resourcesIn(tag: String): Resources {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(configuration).resources
    }

    private val english = resourcesIn("en")
    private val polish = resourcesIn("pl")

    @Test
    fun aPluralTakesEachFormTheLanguageHas() {
        val forms = listOf(1, 3, 5, 12, 22).map { UiText.Plural(R.plurals.i18n_fixture_files, it).resolve(polish) }

        assertEquals(listOf("1 plik", "3 pliki", "5 plików", "12 plików", "22 pliki"), forms)
        assertEquals("5 files", UiText.Plural(R.plurals.i18n_fixture_files, 5).resolve(english))
    }

    @Test
    fun argumentsGoWhereTheLanguagePutsThem() {
        val greeting = uiText(R.string.i18n_fixture_greeting, "Ala", "Kraków")

        assertEquals("Hello Ala, from Kraków", greeting.resolve(english))
        assertEquals("Pozdrowienia z Kraków dla Ala", greeting.resolve(polish))
    }

    @Test
    fun aStringTheLanguageLacksFallsBackToEnglish() {
        assertEquals("Only in English", uiText(R.string.i18n_fixture_only_in_english).resolve(polish))
    }

    @Test
    fun theSameTextFollowsAChangeOfLanguage() {
        val count = UiText.Plural(R.plurals.i18n_fixture_files, 3)

        assertEquals("3 files", count.resolve(english))
        assertEquals("3 pliki", count.resolve(polish))
    }

    @Test
    fun anArgumentThatIsItselfTextResolvesInTheSameLanguage() {
        val note = uiText(R.string.i18n_fixture_wrapped, UiText.Plural(R.plurals.i18n_fixture_files, 2))

        assertEquals("Note: 2 files", note.resolve(english))
        assertEquals("Uwaga: 2 pliki", note.resolve(polish))
    }

    @Test
    fun rawTextIsShownAsItIs() {
        assertEquals("Disk full", UiText.Raw("Disk full").resolve(polish))
    }

    @Test
    fun textResolvesInComposition() {
        rule.setContent { Text(UiText.Plural(R.plurals.i18n_fixture_files, 1).resolve()) }

        rule.onNodeWithText("1 file").assertExists()
    }
}
