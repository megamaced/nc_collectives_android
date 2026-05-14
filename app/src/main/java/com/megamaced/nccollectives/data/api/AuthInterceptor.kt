package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.TokenStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches Basic-auth + OCS headers to outgoing requests, and records each
 * authenticated response with [SessionManager] so the 401-streak signoff
 * (B-2) can keep score.
 *
 * **B-13 / S-3**: the Authorization header is *only* attached when the
 * request's host matches the user's stored Nextcloud host. Today
 * `HostInterceptor` rewrites every request to the stored host before this
 * interceptor sees it, so the check is normally a no-op — but it
 * defence-in-depth means a future feature that issues an absolute URL
 * to a third-party host won't accidentally leak Basic-auth credentials.
 */
@Singleton
class AuthInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val credentials = tokenStore.getCredentials()
            val attach = credentials != null && hostMatches(original.url.host, credentials.host)
            val request = if (attach) {
                checkNotNull(credentials)
                val basic = "${credentials.loginName}:${credentials.appPassword}"
                    .encodeUtf8()
                    .base64()
                val builder = original
                    .newBuilder()
                    .header("Authorization", "Basic $basic")
                    .header("OCS-APIRequest", "true")
                // Nextcloud OCS endpoints reply with XML by default; ask
                // for JSON explicitly. Skip for binary/WebDAV endpoints and
                // for callers that already set an Accept header.
                if (original.url.encodedPath.contains("/ocs/") &&
                    original.header("Accept") == null
                ) {
                    builder.header("Accept", "application/json")
                }
                builder.build()
            } else {
                original
            }

            val response = chain.proceed(request)

            // Only authenticated requests count toward the 401 streak — a
            // public probe (login-poll, etc) returning 401 doesn't mean our
            // token is dead.
            if (attach) {
                sessionManager.onAuthenticatedResponse(response.code)
            }

            return response
        }

        private fun hostMatches(
            requestHost: String,
            credentialsHost: String,
        ): Boolean {
            val stored = credentialsHost.toHttpUrlOrNull()?.host ?: return false
            return requestHost.equals(stored, ignoreCase = true)
        }
    }
