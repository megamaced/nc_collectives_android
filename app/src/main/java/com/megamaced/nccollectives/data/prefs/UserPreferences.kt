package com.megamaced.nccollectives.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

/**
 * Size of page body text, applied to the rendered page, the native
 * editor, and the collaborative editor from one setting.
 *
 * [multiplier] scales the M3 `bodyLarge` size for everything Compose and
 * Markwon draw, and drives the WebView's `textZoom` percentage for the
 * Nextcloud Text editor — the two renderers have nothing else in common,
 * so a single ratio is what keeps them roughly aligned.
 *
 * Multiplies the OS font-size setting rather than replacing it: Compose
 * `sp` and the WebView's default text zoom already track it, so a user
 * at 1.3 system + [Larger] here lands near 1.8. That compounding is why
 * the top step stops at 1.4 — beyond it the Text editor's toolbar, which
 * is laid out in px, starts to crowd the content.
 *
 * Chrome (top bars, lists, settings) is deliberately left alone: this
 * answers "the page text is too small to read", not "the whole UI is too
 * small", which is what the OS setting is for.
 */
enum class TextScale(
    val multiplier: Float,
) {
    Small(0.85f),
    Default(1.0f),
    Large(1.2f),
    Larger(1.4f),
}

/**
 * Routing preference for the edit-page action.
 *
 * - [PreferPlain]: always use the native Markwon editor. Default. Works
 *   offline and avoids the WebView entry's network round-trip + Vue
 *   bootstrap latency on tap-to-edit. The collaborative editor is still
 *   beta-quality on Android (rough chrome leak-through, ad-hoc dark-mode
 *   override, no offline support), so plain is the sane default.
 * - [PreferCollaborative]: open the WebView-backed Nextcloud Text editor
 *   when both the server supports `directEditing` *and* the device is
 *   online. If either is false, falls back to the plain editor with a
 *   one-shot snackbar — the *setting* sticks, the *route* doesn't.
 *
 * **Migration:** older builds shipped `Auto` / `AlwaysPlain` /
 * `AlwaysCollaborative`. The DataStore reader in
 * [UserPreferences.toModel] maps the old names so existing installs
 * don't lose their setting:
 *  - `Auto` → `PreferPlain` (the old Auto routed to web when available;
 *    we deliberately demote rather than promote, since plain is the new
 *    default and users on Auto were the conservative cohort).
 *  - `AlwaysPlain` → `PreferPlain`.
 *  - `AlwaysCollaborative` → `PreferCollaborative`.
 */
enum class EditorPreference { PreferPlain, PreferCollaborative }

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
    val textScale: TextScale = TextScale.Default,
    val syncCadence: SyncCadence = SyncCadence.SixHourly,
    val recentSearches: List<String> = emptyList(),
    val editorPreference: EditorPreference = EditorPreference.PreferPlain,
    /**
     * Collective to open straight into on launch, or null to land on the
     * collective list (the default). Resolved once per app launch by
     * `AuthGateViewModel.resolveStartupCollective`, which drops a stale id
     * if the collective is gone.
     */
    val defaultCollectiveId: Long? = null,
)

/**
 * State for the manual GitHub update check (see [UpdateChecker]), which runs
 * only when the user taps Settings → About → "Check for updates". Persisted
 * in DataStore so the Settings screen can show when the last check happened
 * and which release tag has already been surfaced.
 */
data class UpdateCheckState(
    val lastCheckedAt: Long,
    val lastNotifiedVersion: String?,
)

/**
 * Outcome of the last full sync (`FullSync`). App state rather than a user
 * preference, but it shares DataStore with [UpdateCheckState] for the same
 * reasons: it's two scalars, it has to survive a restart, and sign-out
 * clears it along with everything else.
 *
 * Exists so the Settings screen can answer the question the issue-5 reporter
 * couldn't: is this cache stale because nothing changed, or because syncing
 * has been failing?
 */
data class SyncStatus(
    /** Epoch millis of the last clean sync, or 0 if there's never been one. */
    val lastSuccessAt: Long = 0L,
    /** Epoch millis of the last failed sync, or 0 if none since the last success. */
    val lastFailureAt: Long = 0L,
    val lastFailureMessage: String? = null,
)

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val flow: Flow<UserPrefs> = context.dataStore.data.map { it.toModel() }

        val syncStatus: Flow<SyncStatus> = context.dataStore.data.map { it.toSyncStatus() }

        suspend fun setThemeMode(mode: ThemeMode) {
            context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
        }

        suspend fun setTextScale(scale: TextScale) {
            context.dataStore.edit { it[KEY_TEXT_SCALE] = scale.name }
        }

        suspend fun setSyncCadence(cadence: SyncCadence) {
            context.dataStore.edit { it[KEY_SYNC_CADENCE] = cadence.name }
        }

        suspend fun setEditorPreference(preference: EditorPreference) {
            context.dataStore.edit { it[KEY_EDITOR_PREFERENCE] = preference.name }
        }

        /**
         * Set (or clear, with null) the collective the app opens on launch.
         * Absence of the key — not a sentinel id — means "no default", so a
         * collective can never be confused with "off".
         */
        suspend fun setDefaultCollectiveId(collectiveId: Long?) {
            context.dataStore.edit { prefs ->
                if (collectiveId == null) {
                    prefs.remove(KEY_DEFAULT_COLLECTIVE_ID)
                } else {
                    prefs[KEY_DEFAULT_COLLECTIVE_ID] = collectiveId
                }
            }
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

        /**
         * Stamp a clean sync. Clears any recorded failure: the UI should say
         * "synced 2 minutes ago", not keep showing an error the next sync
         * already recovered from.
         */
        suspend fun recordSyncSuccess(epochMillis: Long) {
            context.dataStore.edit { prefs ->
                prefs[KEY_SYNC_LAST_SUCCESS_AT] = epochMillis
                prefs.remove(KEY_SYNC_LAST_FAILURE_AT)
                prefs.remove(KEY_SYNC_LAST_FAILURE_MESSAGE)
            }
        }

        /** Stamp a failed sync, leaving the last-success time intact. */
        suspend fun recordSyncFailure(
            epochMillis: Long,
            message: String,
        ) {
            context.dataStore.edit { prefs ->
                prefs[KEY_SYNC_LAST_FAILURE_AT] = epochMillis
                prefs[KEY_SYNC_LAST_FAILURE_MESSAGE] = message
            }
        }

        /** Wipe everything — invoked by the sign-out flow before the auth state flips. */
        suspend fun clearAll() {
            context.dataStore.edit { it.clear() }
        }

        suspend fun getUpdateState(): UpdateCheckState {
            val prefs = context.dataStore.data.first()
            return UpdateCheckState(
                lastCheckedAt = prefs[KEY_UPDATE_LAST_CHECKED_AT] ?: 0L,
                lastNotifiedVersion = prefs[KEY_UPDATE_LAST_NOTIFIED_VERSION],
            )
        }

        suspend fun setUpdateLastCheckedAt(epochMillis: Long) {
            context.dataStore.edit { it[KEY_UPDATE_LAST_CHECKED_AT] = epochMillis }
        }

        suspend fun setUpdateLastNotifiedVersion(version: String) {
            context.dataStore.edit { it[KEY_UPDATE_LAST_NOTIFIED_VERSION] = version }
        }

        /**
         * Last `status.php` version we saw, or null if we've never asked.
         * See `ServerVersionTracker` for what it gates.
         */
        suspend fun getLastSeenServerVersion(): String? = context.dataStore.data.first()[KEY_SERVER_VERSION]

        suspend fun setLastSeenServerVersion(version: String) {
            context.dataStore.edit { it[KEY_SERVER_VERSION] = version }
        }

        private fun Preferences.toModel(): UserPrefs {
            val mode = this[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System
            val textScale = this[KEY_TEXT_SCALE]?.let { runCatching { TextScale.valueOf(it) }.getOrNull() }
                ?: TextScale.Default
            val cadence = this[KEY_SYNC_CADENCE]?.let { runCatching { SyncCadence.valueOf(it) }.getOrNull() }
                ?: SyncCadence.SixHourly
            val editorPreference = this[KEY_EDITOR_PREFERENCE]
                ?.let { stored ->
                    // Direct match first, then legacy mapping for installs
                    // that wrote `Auto` / `AlwaysPlain` / `AlwaysCollaborative`
                    // under v2.x. See [EditorPreference] KDoc for the
                    // migration rationale.
                    runCatching { EditorPreference.valueOf(stored) }.getOrNull()
                        ?: when (stored) {
                            "Auto", "AlwaysPlain" -> EditorPreference.PreferPlain
                            "AlwaysCollaborative" -> EditorPreference.PreferCollaborative
                            else -> null
                        }
                }
                ?: EditorPreference.PreferPlain
            return UserPrefs(
                themeMode = mode,
                textScale = textScale,
                syncCadence = cadence,
                recentSearches = this[KEY_RECENT_SEARCHES].toList(),
                editorPreference = editorPreference,
                defaultCollectiveId = this[KEY_DEFAULT_COLLECTIVE_ID],
            )
        }

        private fun Preferences.toSyncStatus(): SyncStatus =
            SyncStatus(
                lastSuccessAt = this[KEY_SYNC_LAST_SUCCESS_AT] ?: 0L,
                lastFailureAt = this[KEY_SYNC_LAST_FAILURE_AT] ?: 0L,
                lastFailureMessage = this[KEY_SYNC_LAST_FAILURE_MESSAGE],
            )

        private fun String?.toList(): List<String> = this?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

        private companion object {
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            val KEY_TEXT_SCALE = stringPreferencesKey("text_scale")
            val KEY_SYNC_CADENCE = stringPreferencesKey("sync_cadence")
            val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
            val KEY_EDITOR_PREFERENCE = stringPreferencesKey("editor_preference")
            val KEY_DEFAULT_COLLECTIVE_ID = longPreferencesKey("default_collective_id")
            val KEY_UPDATE_LAST_CHECKED_AT = longPreferencesKey("update_last_checked_at")
            val KEY_UPDATE_LAST_NOTIFIED_VERSION = stringPreferencesKey("update_last_notified_version")
            val KEY_SERVER_VERSION = stringPreferencesKey("last_seen_server_version")
            val KEY_SYNC_LAST_SUCCESS_AT = longPreferencesKey("sync_last_success_at")
            val KEY_SYNC_LAST_FAILURE_AT = longPreferencesKey("sync_last_failure_at")
            val KEY_SYNC_LAST_FAILURE_MESSAGE = stringPreferencesKey("sync_last_failure_message")

            const val MAX_RECENT_SEARCHES = 10

            // U+001F (Unit Separator) — same rationale as the tag CSV in
            // Mappers.kt: never produced by user input. The earlier literal
            // was empty, which silently corrupted the recent-searches list
            // (split("") explodes into one char per element).
            const val SEP = ""
        }
    }
