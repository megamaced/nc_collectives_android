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
import com.megamaced.nccollectives.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes every trace of the signed-in account's data from the device.
 *
 * Shared by [LogoutHandler] (sign out) and
 * [com.megamaced.nccollectives.data.auth.AccountSwitcher] (switch account),
 * which differ only in what happens on either side of it — the wipe itself
 * has to be identical, because the leak it prevents is identical: one
 * account's cached pages, attachment bytes, thumbnails, WebView session and
 * queued writes must not be visible to the next.
 *
 * Callers are responsible for flipping `SessionManager` into a state that
 * unmounts the UI *before* calling this, so no Room flow observer is live
 * when the tables go.
 *
 * The UI is only half of it: background workers are still running, and
 * cancelling them is asynchronous. [AccountGeneration] is what stops one of
 * their in-flight writes landing behind the wipe; the ordering in [wipe] is
 * what keeps that case rare.
 */
@Singleton
class LocalDataWiper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: NcCollectivesDatabase,
        private val syncScheduler: SyncScheduler,
        private val userPreferences: UserPreferences,
        private val okHttpClient: OkHttpClient,
        private val accountGeneration: AccountGeneration,
    ) {
        /**
         * @param keepDevicePreferences when true, settings that describe the
         * *device* rather than the account — theme, text scale, sync cadence,
         * editor preference — survive. An account switch keeps them: they are
         * how the user has set this phone up, and resetting the theme because
         * they looked at their other server would be a bug. A sign-out clears
         * everything, because the next person to use the install may not be
         * the same person.
         */
        suspend fun wipe(keepDevicePreferences: Boolean) {
            // Issue #20: the order below is the barrier, and it used to run
            // roughly backwards — work was cancelled without waiting, Room
            // was cleared immediately after, and HTTP was only evicted at the
            // very end, by which point a write it might have prevented had
            // already landed.
            //
            // 1. Invalidate first, before anything else and in particular
            //    before the transaction that clears the tables. Every guarded
            //    upsert compares against a generation captured before its
            //    request went out, so from here on a response in flight can
            //    no longer be committed.
            accountGeneration.invalidate()
            // 2. Ask the workers to stop, and wait until WorkManager says
            //    they have.
            syncScheduler.cancelAll()
            // 3. Kill the transport before the tables, not after. An
            //    in-flight request now fails rather than returning a body
            //    somebody wants to write. B-50: evicting the pool also stops
            //    a quick account-A -> account-B change reusing a connection
            //    negotiated under the previous credentials.
            okHttpClient.dispatcher.cancelAll()
            okHttpClient.connectionPool.evictAll()
            // 4. Only now clear Room.
            database.withTransaction {
                database.attachmentDao().clear()
                database.editQueueDao().clear()
                database.pageDao().clear()
                database.collectiveDao().clear()
            }
            if (keepDevicePreferences) {
                userPreferences.clearAccountScoped()
            } else {
                userPreferences.clearAll()
            }
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
            // Main thread: every WebView API below asserts it, and callers
            // run this on `Dispatchers.IO`.
            withContext(Dispatchers.Main) { clearWebViewState() }
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
         * The WebView has a single cookie jar per app, which is the reason
         * the multi-account design switches rather than caching two accounts
         * at once: two live `directediting` sessions on the same host would
         * fight over one cookie.
         *
         * Broad `runCatching` because a device with no WebView provider
         * installed throws from `CookieManager.getInstance()`; a failure to
         * clear state that isn't there must not abort the wipe.
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
            }.onFailure { Timber.w(it, "Couldn't clear WebView state") }
        }
    }
