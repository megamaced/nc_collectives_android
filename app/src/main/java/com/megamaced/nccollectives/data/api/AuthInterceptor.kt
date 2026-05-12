package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import javax.inject.Inject
import javax.inject.Singleton

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
            val request = if (credentials != null) {
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

            if (response.code == 401) {
                sessionManager.onUnauthorised()
            }

            return response
        }
    }
