package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StoredCredentials(
    val host: String,
    val loginName: String,
    val appPassword: String,
)

/**
 * An account as the UI needs to see it: enough to name it, and nothing
 * else. The app password never leaves this file except through
 * [TokenStore.getCredentials], which hands it only to the interceptors that
 * have to put it on the wire.
 */
data class AccountSummary(
    val id: String,
    val host: String,
    val loginName: String,
)

/**
 * Stable identity for an account: a login name at a server. Derived rather
 * than generated so signing in again to an account already on the device
 * updates that account rather than creating a duplicate beside it.
 */
fun accountIdOf(
    host: String,
    loginName: String,
): String = "$loginName@${host.trimEnd('/')}"

@Serializable
private data class PersistedAccount(
    val id: String,
    val host: String,
    val loginName: String,
    val appPassword: String,
)

/**
 * Which account should be live once [removedId] is gone.
 *
 * Pure so the promotion rule — the only decision in [TokenStore.removeAccount]
 * — can be pinned by tests rather than reasoned about behind
 * `EncryptedSharedPreferences`. Removing an account that isn't the active one
 * leaves the active one alone; removing the active one promotes whatever
 * remains, and returns null when nothing does.
 */
internal fun nextActiveAfterRemoval(
    accountIds: List<String>,
    activeId: String?,
    removedId: String,
): String? {
    if (activeId != removedId) return activeId
    return accountIds.firstOrNull { it != removedId }
}

/**
 * Everything the store holds, as one value. Persisted as a single JSON
 * blob so a read is one decrypt rather than one per account per field, and
 * so adding, activating and removing an account are each a single atomic
 * write.
 */
@Serializable
private data class AccountStore(
    val accounts: List<PersistedAccount> = emptyList(),
    val activeId: String? = null,
) {
    val active: PersistedAccount?
        get() = accounts.firstOrNull { it.id == activeId }
}

/**
 * The device's Nextcloud accounts and which one is live.
 *
 * Multi-account (issue #14) is deliberately *switching*, not simultaneous
 * caching: exactly one account's data is in Room at a time, and switching
 * wipes and re-syncs (see `AccountSwitcher`). That is why this class holds
 * several credentials but [getCredentials] — the only thing the network
 * layer ever calls — still answers with one. `AuthInterceptor`,
 * `HostInterceptor`, `PageBodyService` and the two WebView paths therefore
 * needed no changes to become account-aware.
 */
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
         * R-42: memoised store contents. Each miss costs a Tink AEAD decrypt,
         * and the active credential sits on the hot path of every HTTP
         * request — `HostInterceptor` and `AuthInterceptor` both read it, and
         * `PageBodyService.buildWebDavUrl` reads it once per attachment row.
         * `null` means "not cached", so a signed-out store is re-read rather
         * than remembered; that path does no request work anyway. See also
         * B-21 in `SettingsViewModel`, which pushed the (now-cached) read
         * onto `Dispatchers.IO`.
         *
         * `@Volatile` on an immutable holder is enough for readers: an
         * interceptor thread sees either the old reference or the new one,
         * never a half-built object.
         */
        @Volatile
        private var cached: AccountStore? = null

        /**
         * Serialises cache *population* against the writers. Without it a
         * reader that had already read the plaintext out of the store could
         * publish it into the cache after a concurrent sign-out cleared both
         * — leaving stale credentials live after logout. Reads that hit the
         * cache never take the lock.
         */
        private val storeLock = Any()

        private val json = Json { ignoreUnknownKeys = true }

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
                cached = null
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

        /** Credentials for the active account, or null when signed out. */
        fun getCredentials(): StoredCredentials? =
            read().active?.let {
                StoredCredentials(host = it.host, loginName = it.loginName, appPassword = it.appPassword)
            }

        /** Every account on the device, oldest first, without their passwords. */
        fun accounts(): List<AccountSummary> = read().accounts.map { AccountSummary(id = it.id, host = it.host, loginName = it.loginName) }

        fun activeAccountId(): String? = read().active?.id

        /**
         * Add an account, or refresh the app password of one already stored,
         * and make it the active one.
         *
         * Returns the account's id. Signing in again to an account that is
         * already present rewrites its password in place — an app password
         * revoked server-side is re-established without the user losing the
         * account or (when it was already the active one) its cache.
         */
        fun upsertAndActivate(
            host: String,
            loginName: String,
            appPassword: String,
        ): String {
            val id = accountIdOf(host, loginName)
            val entry = PersistedAccount(id = id, host = host, loginName = loginName, appPassword = appPassword)
            synchronized(storeLock) {
                val current = readLocked()
                val accounts = current.accounts.filterNot { it.id == id } + entry
                writeLocked(AccountStore(accounts = accounts, activeId = id))
            }
            return id
        }

        /**
         * Point the store at a different stored account. Returns false — and
         * changes nothing — when [id] names no account, which is what makes
         * a switch racing a removal a no-op rather than a sign-out.
         */
        fun setActiveAccount(id: String): Boolean =
            synchronized(storeLock) {
                val current = readLocked()
                if (current.accounts.none { it.id == id }) return@synchronized false
                writeLocked(current.copy(activeId = id))
                true
            }

        /**
         * Forget one account. Returns the id that is active afterwards, or
         * null if that was the last account and the device is now signed
         * out. Removing the active account promotes the next remaining one
         * rather than leaving the store pointing at nothing.
         */
        fun removeAccount(id: String): String? =
            synchronized(storeLock) {
                val current = readLocked()
                val remaining = current.accounts.filterNot { it.id == id }
                if (remaining.size == current.accounts.size) return@synchronized current.activeId
                val nextActive = nextActiveAfterRemoval(
                    accountIds = current.accounts.map { it.id },
                    activeId = current.activeId,
                    removedId = id,
                )
                writeLocked(AccountStore(accounts = remaining, activeId = nextActive))
                nextActive
            }

        /** Forget every account. The full sign-out path. */
        fun clear() {
            synchronized(storeLock) {
                cached = null
                val store = openPrefs() ?: return
                store.edit().clear().apply()
                cached = AccountStore()
            }
        }

        private fun read(): AccountStore {
            // Fast path: no lock, no decrypt.
            cached?.let { return it }
            return synchronized(storeLock) { readLocked() }
        }

        /** Cache-miss half of [read]. Call with [storeLock] held. */
        private fun readLocked(): AccountStore {
            // Another thread may have populated the cache while this one
            // waited for the lock.
            cached?.let { return it }
            val store = openPrefs() ?: return AccountStore()
            return try {
                val raw = store.getString(KEY_ACCOUNTS, null)
                val parsed = if (raw == null) {
                    migrateSingleAccountLocked(store)
                } else {
                    json.decodeFromString<AccountStore>(raw)
                }
                parsed.also { cached = it }
            } catch (e: Exception) {
                Timber.w(e, "Reading credentials failed; resetting store")
                prefs = null
                cached = null
                resetPrefsFile()
                AccountStore()
            }
        }

        /**
         * Read the pre-multi-account layout — one account across three flat
         * keys — and rewrite it as a one-entry store. Runs at most once per
         * install: the flat keys are dropped by the same write.
         *
         * Returning an empty store when the flat keys are absent is the
         * signed-out case, not a failure.
         */
        private fun migrateSingleAccountLocked(store: SharedPreferences): AccountStore {
            val host = store.getString(LEGACY_KEY_HOST, null)
            val loginName = store.getString(LEGACY_KEY_LOGIN_NAME, null)
            val appPassword = store.getString(LEGACY_KEY_APP_PASSWORD, null)
            if (host == null || loginName == null || appPassword == null) return AccountStore()
            Timber.i("Migrating the stored credential to the multi-account layout")
            val id = accountIdOf(host, loginName)
            val migrated = AccountStore(
                accounts = listOf(
                    PersistedAccount(id = id, host = host, loginName = loginName, appPassword = appPassword),
                ),
                activeId = id,
            )
            writeLocked(migrated)
            return migrated
        }

        /** Persist [store] and republish the cache. Call with [storeLock] held. */
        private fun writeLocked(store: AccountStore) {
            // Drop first: if the write below can't open the prefs, the cache
            // must not keep serving what we failed to persist.
            cached = null
            val prefsFile = openPrefs() ?: return
            prefsFile
                .edit()
                .putString(KEY_ACCOUNTS, json.encodeToString(store))
                .remove(LEGACY_KEY_HOST)
                .remove(LEGACY_KEY_LOGIN_NAME)
                .remove(LEGACY_KEY_APP_PASSWORD)
                .apply()
            cached = store
        }

        companion object {
            // Filename mirrors the entry in backup_rules.xml that excludes
            // this file from cloud backup / device transfer.
            private const val PREFS_FILE = "nc_collectives_secure_prefs"
            private const val KEY_ACCOUNTS = "accounts"

            // Pre-multi-account keys. Read once by `migrateSingleAccountLocked`
            // and removed by the write that follows; kept named here so the
            // migration stays readable rather than using bare literals.
            private const val LEGACY_KEY_HOST = "host"
            private const val LEGACY_KEY_LOGIN_NAME = "login_name"
            private const val LEGACY_KEY_APP_PASSWORD = "app_password"
        }
    }
