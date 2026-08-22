package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import androidx.room.withTransaction
import coil3.SingletonImageLoader
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the multi-step sign-out flow. Runs on a singleton-scoped
 * supervisor scope so the work completes even when the caller's
 * `viewModelScope` is torn down as the scaffold swaps to `LoginScreen`.
 *
 * Order of operations (B-5, post-audit):
 *  1. `sessionManager.beginSignOut()` flips `authState` to
 *     `Unauthenticated` and arms the 401-suppression flag so any
 *     in-flight `SyncWorker` / `EditFlushWorker` requests that race
 *     against the wipe can't trigger a second sign-out cycle. The
 *     scaffold observes the state change and mounts `LoginScreen`
 *     on the next frame — observers of Room flows are torn down
 *     before the DB transaction below begins.
 *  2. Cancel every WorkManager job we own so the workers don't fire
 *     against the user's now-empty cache.
 *  3. Wipe every Room table in a single transaction.
 *  4. Clear the user's DataStore preferences (recent searches, theme,
 *     cadence). Keeping prefs across user accounts would leak the
 *     previous user's recent searches. Then delete the attachment cache
 *     directories, which hold raw file bytes Room doesn't track.
 *  5. Clear Coil's image caches and wipe the WebView's own storage —
 *     see [clearWebViewState].
 *  6. `sessionManager.endSignOut()` clears the encrypted token store
 *     and releases the 401-suppression flag.
 */
@Singleton
class LogoutHandler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: NcCollectivesDatabase,
        private val sessionManager: SessionManager,
        private val syncScheduler: SyncScheduler,
        private val userPreferences: UserPreferences,
        private val sharePayloadHolder: SharePayloadHolder,
        private val okHttpClient: OkHttpClient,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun signOut() {
            sessionManager.beginSignOut()
            // S-16: drop any pending share payload before the next session
            // starts. A share intent captured under user A would otherwise
            // still be sitting in the process-wide singleton when user B
            // signs in on the same install, and would pop the share UI
            // with user A's payload targeting user B's Nextcloud.
            sharePayloadHolder.consume()
            scope.launch {
                syncScheduler.cancelAll()
                database.withTransaction {
                    database.attachmentDao().clear()
                    database.editQueueDao().clear()
                    database.pageDao().clear()
                    database.collectiveDao().clear()
                }
                userPreferences.clearAll()
                // Raw attachment bytes live in the cache dir, not Room, so
                // wiping the tables above orphans them rather than removing
                // them. Without this a PDF the previous account opened stays
                // readable on disk through the next account's session.
                AttachmentRepositoryImpl.clearCachedFiles(context)
                // Coil keeps its own memory + disk caches, keyed on the
                // attachment's WebDAV URL, of every thumbnail fetched under
                // the previous credentials — the same class of on-disk
                // residue as the attachment cache dir above, just owned by
                // the image loader instead of us.
                SingletonImageLoader.get(context).run {
                    memoryCache?.clear()
                    diskCache?.clear()
                }
                // Main thread: every WebView API below asserts it, and this
                // block runs on `Dispatchers.IO`.
                withContext(Dispatchers.Main) { clearWebViewState() }
                // B-50: evict the OkHttp connection pool so a quick
                // user-A → user-B sign-in cycle can't reuse a still-warm
                // connection negotiated under the previous credentials.
                // `dispatcher.cancelAll()` aborts any in-flight requests
                // (e.g. a sync job racing the sign-out) so they don't
                // hit the server after the local wipe.
                okHttpClient.dispatcher.cancelAll()
                okHttpClient.connectionPool.evictAll()
                sessionManager.endSignOut()
            }
        }

        /**
         * S-25: wipe the state the editor WebView keeps on its own, which
         * none of the wipes above reach.
         *
         * `domStorageEnabled` is load-bearing for the embedded editor —
         * Nextcloud Text v34+ persists the Yjs document in the WebView's
         * IndexedDB — so the previous account's *page text* sits in the
         * WebView's data directory, alongside the Nextcloud session cookie
         * the `directediting` load set. Both outlive a Room + DataStore +
         * token-store wipe, which is exactly the cross-account leak the rest
         * of this class exists to prevent.
         *
         * Broad `runCatching` because a device with no WebView provider
         * installed throws from `CookieManager.getInstance()`; a failure to
         * clear state that isn't there must not abort the sign-out.
         */
        @Suppress("DEPRECATION") // WebViewDatabase.clearFormData has no replacement.
        private fun clearWebViewState() {
            runCatching {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    // Cookies are removed asynchronously in-memory; without
                    // the flush the on-disk store can survive the wipe.
                    flush()
                }
                WebStorage.getInstance().deleteAllData()
                WebViewDatabase.getInstance(context).clearFormData()
            }.onFailure { Timber.w(it, "Couldn't clear WebView state during sign-out") }
        }
    }
