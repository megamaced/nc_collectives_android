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

/**
 * Period for the periodic metadata sync. `Off` cancels the WorkManager
 * job entirely so the user only sees one-shot foreground refreshes.
 *
 * R-38 was triaged for a sealed-class rewrite to replace the
 * `hours: Long?` + `null = Off` sentinel. Investigation: enum gives
 * exhaustive `when`, `.entries` iteration for the Settings list, and a
 * trivial DataStore round-trip via `.name` / `.valueOf`. The sealed-class
 * shape would force a companion-object `all` list and case-by-case
 * persistence with no safety improvement. Keeping the enum.
 */
enum class SyncCadence(
    /** Period in hours, or `null` to mean the worker shouldn't run at all. */
    val hours: Long?,
) {
    Off(null),
    Hourly(1),
    SixHourly(6),
    TwiceDaily(12),
    Daily(24),
}

data class UserPrefs(
    val themeMode: ThemeMode = ThemeMode.System,
    val syncCadence: SyncCadence = SyncCadence.SixHourly,
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

        suspend fun setSyncCadence(cadence: SyncCadence) {
            context.dataStore.edit { it[KEY_SYNC_CADENCE] = cadence.name }
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

        /** Wipe everything — invoked by the sign-out flow before the auth state flips. */
        suspend fun clearAll() {
            context.dataStore.edit { it.clear() }
        }

        private fun Preferences.toModel(): UserPrefs {
            val mode = this[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System
            val cadence = this[KEY_SYNC_CADENCE]?.let { runCatching { SyncCadence.valueOf(it) }.getOrNull() }
                ?: SyncCadence.SixHourly
            return UserPrefs(
                themeMode = mode,
                syncCadence = cadence,
                recentSearches = this[KEY_RECENT_SEARCHES].toList(),
            )
        }

        private fun String?.toList(): List<String> = this?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

        private companion object {
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            val KEY_SYNC_CADENCE = stringPreferencesKey("sync_cadence")
            val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")

            const val MAX_RECENT_SEARCHES = 10

            // U+001F (Unit Separator) — same rationale as the tag CSV in
            // Mappers.kt: never produced by user input. The earlier literal
            // was empty, which silently corrupted the recent-searches list
            // (split("") explodes into one char per element).
            const val SEP = ""
        }
    }
