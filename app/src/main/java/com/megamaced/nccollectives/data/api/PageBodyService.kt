package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a page's markdown body over WebDAV. The Collectives REST API only
 * returns metadata — the markdown itself lives as a plain file in the user's
 * Files area under `{collectivePath}/{filePath}/{fileName}` and is accessed
 * via `GET /remote.php/dav/files/{loginName}/...`.
 *
 * Uses the shared authenticated [OkHttpClient] so the [AuthInterceptor]
 * attaches Basic auth. [HostInterceptor] rewrites the placeholder URL we
 * build to the real Nextcloud host at request time.
 */
@Singleton
class PageBodyService
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val tokenStore: TokenStore,
    ) {
        suspend fun fetchBody(
            collectivePath: String,
            filePath: String,
            fileName: String,
        ): ApiResult<String> =
            withContext(Dispatchers.IO) {
                apiCall {
                    val credentials = tokenStore.getCredentials()
                        ?: throw IllegalStateException("Not authenticated")
                    val url = buildWebDavUrl(
                        host = credentials.host,
                        loginName = credentials.loginName,
                        collectivePath = collectivePath,
                        filePath = filePath,
                        fileName = fileName,
                    )
                    val request = Request
                        .Builder()
                        .url(url)
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException(
                                "WebDAV GET returned ${response.code} for ${response.request.url.encodedPath}",
                            )
                        }
                        response.body?.string().orEmpty()
                    }
                }
            }

        private fun buildWebDavUrl(
            host: String,
            loginName: String,
            collectivePath: String,
            filePath: String,
            fileName: String,
        ): String {
            val base = host.toHttpUrlOrNull()
                ?: throw IllegalStateException("Stored host is not a valid URL")
            val builder = base
                .newBuilder()
                .addPathSegment("remote.php")
                .addPathSegment("dav")
                .addPathSegment("files")
                .addPathSegment(loginName)
            collectivePath
                .trim('/')
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach(builder::addPathSegment)
            if (filePath.isNotEmpty()) {
                filePath
                    .trim('/')
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .forEach(builder::addPathSegment)
            }
            builder.addPathSegment(fileName)
            return builder.build().toString()
        }
    }
