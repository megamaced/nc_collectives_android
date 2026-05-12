package com.megamaced.nccollectives.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

data class UserPrefs(
    val themeMode: ThemeMode = ThemeMode.System,
    val recentSearches: List<String> = emptyList(),
)

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val flow: Flow<UserPrefs> = context.dataStore.data.map { it.toModel() }

        suspend fun setThemeMode(mode: ThemeMode) {
            context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
        }

        suspend fun pushRecentSearch(term: String) {
            val cleaned = term.trim()
            if (cleaned.isEmpty()) return
            context.dataStore.edit { prefs ->
                val current = prefs[KEY_RECENT_SEARCHES].toList()
                val deduped = (listOf(cleaned) + current.filterNot { it.equals(cleaned, ignoreCase = true) })
                    .take(MAX_RECENT_SEARCHES)
                prefs[KEY_RECENT_SEARCHES] = deduped.joinToString(SEP)
            }
        }

        suspend fun clearRecentSearches() {
            context.dataStore.edit { it.remove(KEY_RECENT_SEARCHES) }
        }

        private fun Preferences.toModel(): UserPrefs {
            val mode = this[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System
            return UserPrefs(
                themeMode = mode,
                recentSearches = this[KEY_RECENT_SEARCHES].toList(),
            )
        }

        private fun String?.toList(): List<String> = this?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

        private companion object {
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
            const val MAX_RECENT_SEARCHES = 10

            // U+001F (Unit Separator) — same rationale as the tag CSV in
            // Mappers.kt: never produced by user input.
            const val SEP = ""
        }
    }
