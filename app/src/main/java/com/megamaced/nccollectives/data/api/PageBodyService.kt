package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** A page body along with the WebDAV ETag the server returned for it. */
data class PageBody(
    val markdown: String,
    val etag: String?,
)

/**
 * Fetches and saves a page's markdown body over WebDAV. The Collectives REST
 * API only returns metadata — the markdown itself lives as a plain file in
 * the user's Files area under `{collectivePath}/{filePath}/{fileName}` and
 * is accessed via `GET`/`PUT` on `/remote.php/dav/files/{loginName}/...`.
 *
 * Uses the shared authenticated [OkHttpClient] so the [AuthInterceptor]
 * attaches Basic auth; [HostInterceptor] rewrites the placeholder URL we
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
        ): ApiResult<PageBody> =
            withContext(Dispatchers.IO) {
                apiCall {
                    val url = buildWebDavUrl(collectivePath, filePath, fileName)
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
                        PageBody(
                            markdown = response.body?.string().orEmpty(),
                            etag = response.header("ETag")?.trim('"'),
                        )
                    }
                }
            }

        /**
         * Writes [body] to the page's WebDAV path. If [baseEtag] is non-null
         * it is sent as `If-Match`, so a 412 fires when the server-side body
         * has changed since [baseEtag] was captured. Returns the new ETag.
         */
        suspend fun saveBody(
            collectivePath: String,
            filePath: String,
            fileName: String,
            body: String,
            baseEtag: String?,
        ): ApiResult<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val url = buildWebDavUrl(collectivePath, filePath, fileName)
                    val requestBuilder = Request
                        .Builder()
                        .url(url)
                        .put(body.toRequestBody(MARKDOWN.toMediaType()))
                    if (baseEtag != null) {
                        requestBuilder.header("If-Match", "\"$baseEtag\"")
                    }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        when (response.code) {
                            in 200..299 -> ApiResult.Success(response.header("ETag")?.trim('"'))
                            401 -> ApiResult.Unauthorised
                            412 -> ApiResult.Conflict
                            else -> ApiResult.HttpError(response.code, response.message)
                        }
                    }
                } catch (e: java.io.IOException) {
                    ApiResult.NetworkError(e)
                } catch (e: Exception) {
                    ApiResult.Unexpected(e)
                }
            }

        private fun buildWebDavUrl(
            collectivePath: String,
            filePath: String,
            fileName: String,
        ): String {
            val credentials = tokenStore.getCredentials()
                ?: throw IllegalStateException("Not authenticated")
            val base = credentials.host.toHttpUrlOrNull()
                ?: throw IllegalStateException("Stored host is not a valid URL")
            val builder = base
                .newBuilder()
                .addPathSegment("remote.php")
                .addPathSegment("dav")
                .addPathSegment("files")
                .addPathSegment(credentials.loginName)
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

        private companion object {
            const val MARKDOWN = "text/markdown; charset=utf-8"
        }
    }
