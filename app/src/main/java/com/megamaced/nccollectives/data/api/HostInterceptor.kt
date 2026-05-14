package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.TokenStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites every outgoing request's scheme/host/port/path-prefix to point at
 * the user's Nextcloud instance from [TokenStore]. Retrofit needs a static
 * base URL at construction time, so we use a placeholder and override at
 * request time.
 *
 * **B-12**: the host stored in [TokenStore] may include a subdirectory
 * prefix (e.g. `https://example.com/nextcloud`); without preserving that
 * prefix every OCS / WebDAV call hits the bare domain and 404s. We now
 * concatenate the stored URL's `encodedPath` in front of the request's
 * own path before forwarding.
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
            val prefix = target.encodedPath.trimEnd('/')
            val rewritten = original.url
                .newBuilder()
                .scheme(target.scheme)
                .host(target.host)
                .port(target.port)
                .apply {
                    if (prefix.isNotEmpty()) {
                        // OkHttp's encodedPath setter expects a leading '/'.
                        encodedPath(prefix + original.url.encodedPath)
                    }
                }.build()

            return chain.proceed(original.newBuilder().url(rewritten).build())
        }
    }
