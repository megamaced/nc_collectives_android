package com.megamaced.nccollectives.data

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.ServerStatusService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.prefs.UserPreferences
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the Nextcloud server's version across app runs so the WebView's
 * HTTP cache can be dropped when it changes.
 *
 * **Why this exists.** The embedded Nextcloud Text editor loads its JS/CSS
 * from the user's own server, and the WebView's HTTP cache (`LOAD_DEFAULT`)
 * already keeps those assets between sessions — which is most of what makes
 * repeat opens fast. The upstream Text maintainer pointed out the safety
 * property that makes leaning on that cache reasonable (GitHub issue #1,
 * comment 5031076685): Text is a *bundled* app, so its assets can only
 * change when the server version changes, and `status.php`'s `version` is a
 * cheap way to notice.
 *
 * Nextcloud does append a cachebuster to its asset URLs, so a stale entry is
 * normally content-identical to a fresh one and this is belt-and-braces. It
 * earns its keep for anything served *without* a cachebuster, where a server
 * upgrade would otherwise leave the WebView holding an asset from the
 * previous Text version indefinitely.
 *
 * **What this deliberately does not do.** It does not enable offline use of
 * the collaborative editor, and no amount of asset caching would: opening a
 * session is a live `POST …/directEditing/open` that returns a one-shot
 * token, and the WebView then loads a *server-rendered* page at that URL. No
 * network, no session, no editor — regardless of what's cached. The native
 * Markwon editor remains the only cold-start-offline path, which is why
 * `EditorPreference.PreferPlain` is still the default.
 */
@Singleton
class ServerVersionTracker
    @Inject
    constructor(
        private val service: ServerStatusService,
        private val userPreferences: UserPreferences,
    ) {
        /**
         * Probe `status.php` and compare against the stored version.
         *
         * Returns true when the server version has **changed since a
         * previously recorded one** — the caller should drop caches keyed on
         * server-side assets. Returns false on a first-ever probe (nothing to
         * invalidate: there is no cache from an older version yet) and false
         * on any network/parse failure, so a flaky connection degrades to
         * "keep using what we have" rather than clearing the cache on every
         * editor open.
         */
        suspend fun serverVersionChanged(): Boolean {
            val result = apiCall { service.getStatus() }
            if (result !is ApiResult.Success) {
                Timber.tag(TAG).d("status.php probe failed; leaving cached assets alone")
                return false
            }
            val version = result.data.version.takeIf { it.isNotEmpty() }
                ?: result.data.versionstring.takeIf { it.isNotEmpty() }
                ?: return false
            val previous = userPreferences.getLastSeenServerVersion()
            userPreferences.setLastSeenServerVersion(version)
            if (previous == null) {
                Timber.tag(TAG).d("First server-version probe: %s", version)
                return false
            }
            if (previous == version) return false
            Timber.tag(TAG).i("Server version changed %s → %s; dropping WebView cache", previous, version)
            return true
        }

        private companion object {
            const val TAG = "ServerVersion"
        }
    }
