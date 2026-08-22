package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StoredCredentials(
    val host: String,
    val loginName: String,
    val appPassword: String,
)

@Singleton
class TokenStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        // Cached on first successful open. `null` means either not-yet-opened
        // or open-failed (in which case [openPrefs] will retry next call).
        @Volatile
        private var prefs: SharedPreferences? = null

        /**
         * R-42: memoised result of [getCredentials]. Each miss costs three
         * Tink AEAD decrypts, and the getter sits on the hot path of every
         * HTTP request — `HostInterceptor` and `AuthInterceptor` both call
         * it, and `PageBodyService.buildWebDavUrl` calls it once per
         * attachment row. `null` means "not cached", so a signed-out store
         * is re-read rather than remembered; that path does no request work
         * anyway. See also B-21 in `SettingsViewModel`, which pushed the
         * (now-cached) read onto `Dispatchers.IO`.
         *
         * `@Volatile` on an immutable holder is enough for readers: an
         * interceptor thread sees either the old reference or the new one,
         * never a half-built object.
         */
        @Volatile
        private var cachedCredentials: StoredCredentials? = null

        /**
         * Serialises cache *population* against the writers ([saveCredentials],
         * [clear], and the wipe-and-retry recovery in [openPrefs]). Without
         * it a reader that had already read the plaintext out of the store
         * could publish it into the cache after a concurrent sign-out
         * cleared both — leaving stale credentials live after logout. Reads
         * that hit the cache never take the lock.
         */
        private val credentialsLock = Any()

        /**
         * Open or reopen the encrypted prefs. On `AEADBadTagException`/
         * `KeyStoreException`/`SecurityException` — typically caused by a
         * Keystore reset (factory restore, OEM wipe) or a corrupted Tink
         * keyset on disk — the prefs file is deleted and a fresh empty
         * store is created. The user is treated as unauthenticated, which
         * routes back to the login flow naturally on the next session
         * refresh. Previously this method propagated and crashed the app
         * on launch from `SessionManager.init` (S-19).
         */
        private fun openPrefs(): SharedPreferences? {
            prefs?.let { return it }
            return try {
                val masterKey = MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences
                    .create(
                        context,
                        PREFS_FILE,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    ).also { prefs = it }
            } catch (e: Exception) {
                // Catch broad: Tink wraps a wide cone of failures and the
                // recovery is always the same — wipe + start over.
                Timber.w(e, "Encrypted prefs unreadable; wiping and re-creating")
                // R-42: the file we just wiped is the only source of truth
                // for the cache, so anything memoised from it is now stale.
                cachedCredentials = null
                resetPrefsFile()
                null
            }
        }

        private fun resetPrefsFile() {
            try {
                File(context.filesDir.parentFile, "shared_prefs/$PREFS_FILE.xml").delete()
            } catch (e: SecurityException) {
                Timber.w(e, "Couldn't delete corrupted prefs file")
            }
        }

        fun getCredentials(): StoredCredentials? {
            // Fast path: no lock, no decrypt.
            cachedCredentials?.let { return it }
            return synchronized(credentialsLock) { readCredentialsLocked() }
        }

        /** Cache-miss half of [getCredentials]. Call with [credentialsLock] held. */
        private fun readCredentialsLocked(): StoredCredentials? {
            // Another thread may have populated the cache while this one
            // waited for the lock.
            cachedCredentials?.let { return it }
            val store = openPrefs() ?: return null
            return try {
                val host = store.getString(KEY_HOST, null) ?: return null
                val loginName = store.getString(KEY_LOGIN_NAME, null) ?: return null
                val appPassword = store.getString(KEY_APP_PASSWORD, null) ?: return null
                StoredCredentials(host, loginName, appPassword).also { cachedCredentials = it }
            } catch (e: Exception) {
                Timber.w(e, "Reading credentials failed; resetting store")
                prefs = null
                cachedCredentials = null
                resetPrefsFile()
                null
            }
        }

        fun saveCredentials(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            synchronized(credentialsLock) {
                // Drop first: if the write below can't open the store, the
                // cache must not keep serving the previous account.
                cachedCredentials = null
                val store = openPrefs() ?: return
                store
                    .edit()
                    .putString(KEY_HOST, host)
                    .putString(KEY_LOGIN_NAME, loginName)
                    .putString(KEY_APP_PASSWORD, appPassword)
                    .apply()
                cachedCredentials = StoredCredentials(host, loginName, appPassword)
            }
        }

        fun clear() {
            synchronized(credentialsLock) {
                cachedCredentials = null
                val store = openPrefs() ?: return
                store.edit().clear().apply()
            }
        }

        companion object {
            // Filename mirrors the entry in backup_rules.xml that excludes
            // this file from cloud backup / device transfer.
            private const val PREFS_FILE = "nc_collectives_secure_prefs"
            private const val KEY_HOST = "host"
            private const val KEY_LOGIN_NAME = "login_name"
            private const val KEY_APP_PASSWORD = "app_password"
        }
    }
