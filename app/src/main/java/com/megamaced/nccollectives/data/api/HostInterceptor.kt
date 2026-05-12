package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.TokenStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites every outgoing request's scheme/host/port to point at the user's
 * Nextcloud instance from [TokenStore]. Retrofit needs a static base URL at
 * construction time, so we use a placeholder and override at request time.
 */
@Singleton
class HostInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val credentials = tokenStore.getCredentials() ?: return chain.proceed(chain.request())
            val target = credentials.host.toHttpUrlOrNull() ?: return chain.proceed(chain.request())

            val original = chain.request()
            val rewritten = original.url
                .newBuilder()
                .scheme(target.scheme)
                .host(target.host)
                .port(target.port)
                .build()

            return chain.proceed(original.newBuilder().url(rewritten).build())
        }
    }
