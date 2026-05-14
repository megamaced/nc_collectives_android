package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
                try {
                    val url = buildWebDavUrl(collectivePath, filePath, fileName)
                    val request = Request
                        .Builder()
                        .url(url)
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            in 200..299 ->
                                ApiResult.Success(
                                    PageBody(
                                        markdown = response.body?.string().orEmpty(),
                                        etag = response.header("ETag")?.trim('"'),
                                    ),
                                )
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

        /**
         * Moves the page file at `(collectivePath, filePath, fileName)` to a
         * new location. The destination is built from `(destCollectivePath,
         * destFilePath, destFileName)`. Used by Batch 11 for rename / move.
         *
         * Leaf pages only. Folder pages (the file is `Readme.md` inside a
         * directory that holds children) require moving the whole directory
         * and aren't supported here yet.
         */
        suspend fun moveFile(
            collectivePath: String,
            filePath: String,
            fileName: String,
            destCollectivePath: String,
            destFilePath: String,
            destFileName: String,
        ): ApiResult<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val sourceUrl = buildWebDavUrl(collectivePath, filePath, fileName)
                    val destUrl = buildWebDavUrl(destCollectivePath, destFilePath, destFileName)
                    val request = Request
                        .Builder()
                        .url(sourceUrl)
                        .method("MOVE", null)
                        .header("Destination", destUrl)
                        .header("Overwrite", "F")
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            in 200..299 -> ApiResult.Success(Unit)
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

        /**
         * Creates a WebDAV collection (directory). Returns success if the
         * directory was created (`201`) or already exists (`405`). Used to
         * lazily materialise `.attachments.<pageId>` before the first upload.
         */
        suspend fun ensureCollection(
            collectivePath: String,
            filePath: String,
            directoryName: String,
        ): ApiResult<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val url = buildWebDavUrl(collectivePath, filePath, directoryName, asCollection = true)
                    val request = Request
                        .Builder()
                        .url(url)
                        .method("MKCOL", null)
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            in 200..299, 405 -> ApiResult.Success(Unit)
                            401 -> ApiResult.Unauthorised
                            else -> ApiResult.HttpError(response.code, response.message)
                        }
                    }
                } catch (e: java.io.IOException) {
                    ApiResult.NetworkError(e)
                } catch (e: Exception) {
                    ApiResult.Unexpected(e)
                }
            }

        /**
         * Uploads a binary file at `(collectivePath, filePath, fileName)`.
         * Server overwrites any existing file with the same name — callers
         * are expected to disambiguate names beforehand.
         */
        suspend fun uploadFile(
            collectivePath: String,
            filePath: String,
            fileName: String,
            body: RequestBody,
        ): ApiResult<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val url = buildWebDavUrl(collectivePath, filePath, fileName)
                    val request = Request
                        .Builder()
                        .url(url)
                        .put(body)
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            in 200..299 -> ApiResult.Success(response.header("ETag")?.trim('"'))
                            401 -> ApiResult.Unauthorised
                            else -> ApiResult.HttpError(response.code, response.message)
                        }
                    }
                } catch (e: java.io.IOException) {
                    ApiResult.NetworkError(e)
                } catch (e: Exception) {
                    ApiResult.Unexpected(e)
                }
            }

        /**
         * Build a fully-qualified WebDAV URL string for `(collectivePath,
         * filePath, fileName)`. Exposed so the UI can hand attachment URLs
         * to Coil — `HostInterceptor` rewrites the host at request time
         * anyway, but we use the real host here so the cache key is stable.
         */
        fun resourceUrl(
            collectivePath: String,
            filePath: String,
            fileName: String,
        ): String = buildWebDavUrl(collectivePath, filePath, fileName)

        private fun buildWebDavUrl(
            collectivePath: String,
            filePath: String,
            fileName: String,
            asCollection: Boolean = false,
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
            val result = builder.build().toString()
            // OkHttp's HttpUrl trims trailing slashes; WebDAV PROPFIND/MKCOL
            // on a directory benefits from the trailing slash so the server
            // never returns a 301 redirect to the canonical form.
            return if (asCollection && !result.endsWith('/')) "$result/" else result
        }

        private companion object {
            const val MARKDOWN = "text/markdown; charset=utf-8"
        }
    }
