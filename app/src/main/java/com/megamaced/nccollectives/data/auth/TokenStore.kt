package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
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
        @ApplicationContext context: Context,
    ) {
        private val prefs: SharedPreferences by lazy {
            val masterKey = MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun getCredentials(): StoredCredentials? {
            val host = prefs.getString(KEY_HOST, null) ?: return null
            val loginName = prefs.getString(KEY_LOGIN_NAME, null) ?: return null
            val appPassword = prefs.getString(KEY_APP_PASSWORD, null) ?: return null
            return StoredCredentials(host, loginName, appPassword)
        }

        fun saveCredentials(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            prefs
                .edit()
                .putString(KEY_HOST, host)
                .putString(KEY_LOGIN_NAME, loginName)
                .putString(KEY_APP_PASSWORD, appPassword)
                .apply()
        }

        fun clear() {
            prefs.edit().clear().apply()
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
