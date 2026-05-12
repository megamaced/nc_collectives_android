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

        private fun Preferences.toModel(): UserPrefs {
            val mode = this[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System
            return UserPrefs(themeMode = mode)
        }

        private companion object {
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        }
    }
