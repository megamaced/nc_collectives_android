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
            val store = openPrefs() ?: return null
            return try {
                val host = store.getString(KEY_HOST, null) ?: return null
                val loginName = store.getString(KEY_LOGIN_NAME, null) ?: return null
                val appPassword = store.getString(KEY_APP_PASSWORD, null) ?: return null
                StoredCredentials(host, loginName, appPassword)
            } catch (e: Exception) {
                Timber.w(e, "Reading credentials failed; resetting store")
                prefs = null
                resetPrefsFile()
                null
            }
        }

        fun saveCredentials(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            val store = openPrefs() ?: return
            store
                .edit()
                .putString(KEY_HOST, host)
                .putString(KEY_LOGIN_NAME, loginName)
                .putString(KEY_APP_PASSWORD, appPassword)
                .apply()
        }

        fun clear() {
            val store = openPrefs() ?: return
            store.edit().clear().apply()
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
