package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.auth.serverHostOf
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
 * request's host matches the user's stored Nextcloud host, so a feature
 * that issues an absolute URL to a third-party host can't leak Basic-auth
 * credentials.
 *
 * **S-23**: that host check alone can no longer see what it was written to
 * catch — [HostInterceptor] runs first in the chain and has already
 * rewritten scheme/host/port to the stored host (preserving path and
 * query), so by the time we look, *every* surviving request matches. The
 * ordering can't be flipped without losing the rewrite that keeps
 * credentials off third-party hosts in the first place, so provenance is
 * carried explicitly instead: [HostInterceptor] stamps a [RequestOrigin]
 * on requests whose URL the app itself constructed, and refuses the rest.
 * Requiring the tag here means a URL that arrived in a server response —
 * an image ref planted in a shared page body, say — cannot be handed our
 * credentials even if it somehow reaches this interceptor untagged.
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
            val vouchedFor = original.tag(RequestOrigin::class.java) != null
            val attach = credentials != null &&
                vouchedFor &&
                hostMatches(original.url.host, credentials.host)
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
            val stored = serverHostOf(credentialsHost) ?: return false
            return requestHost.equals(stored, ignoreCase = true)
        }
    }
