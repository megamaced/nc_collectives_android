package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.ServerStringValidation
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
        ): ApiResult<PageBody> {
            val request = Request
                .Builder()
                .url(buildWebDavUrl(collectivePath, filePath, fileName))
                .get()
                .build()
            return webDavCall(request) { response ->
                PageBody(
                    markdown = response.body?.string().orEmpty(),
                    etag = normaliseEtag(response.header("ETag")),
                )
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
        ): ApiResult<String?> {
            val builder = Request
                .Builder()
                .url(buildWebDavUrl(collectivePath, filePath, fileName))
                .put(body.toRequestBody(MARKDOWN.toMediaType()))
            if (baseEtag != null) {
                builder.header("If-Match", "\"$baseEtag\"")
            }
            return webDavCall(builder.build()) { response -> normaliseEtag(response.header("ETag")) }
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
        ): ApiResult<Unit> {
            val request = Request
                .Builder()
                .url(buildWebDavUrl(collectivePath, filePath, directoryName, asCollection = true))
                .method("MKCOL", null)
                .build()
            return webDavCall(request, extraSuccessCodes = setOf(405)) { }
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
        ): ApiResult<String?> {
            val request = Request
                .Builder()
                .url(buildWebDavUrl(collectivePath, filePath, fileName))
                .put(body)
                .build()
            return webDavCall(request) { response -> normaliseEtag(response.header("ETag")) }
        }

        /**
         * R-20: shared WebDAV call boilerplate. Dispatches the call on the
         * IO pool, maps response codes into [ApiResult] (200..299 + any
         * [extraSuccessCodes] → Success via [onSuccess]; 401 → Unauthorised;
         * 412 → Conflict; rest → HttpError) and routes IOException →
         * NetworkError + anything else → Unexpected. Eliminates four
         * identical try/withContext/use blocks that previously diverged
         * subtly (e.g. ensureCollection accepted 405; only some sites
         * normalised the ETag).
         */
        private suspend fun <T> webDavCall(
            request: Request,
            extraSuccessCodes: Set<Int> = emptySet(),
            onSuccess: (okhttp3.Response) -> T,
        ): ApiResult<T> =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code in 200..299 || response.code in extraSuccessCodes ->
                                ApiResult.Success(onSuccess(response))
                            response.code == 401 -> ApiResult.Unauthorised
                            response.code == 412 -> ApiResult.Conflict
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
            // S-14′: every server-supplied segment is validated before
            // being spliced into the URL. `addPathSegment` percent-encodes
            // an embedded `/` but leaves `..` intact — without this gate a
            // compromised server returning `collectivePath="../../"` would
            // walk the WebDAV request up the user's Files tree.
            appendSegments(builder, collectivePath)
            if (filePath.isNotEmpty()) {
                appendSegments(builder, filePath)
            }
            val finalSegment = ServerStringValidation.cleanPathSegment(fileName)
                ?: throw IllegalStateException("Refusing to build WebDAV URL with hostile fileName")
            builder.addPathSegment(finalSegment)
            val result = builder.build().toString()
            // OkHttp's HttpUrl trims trailing slashes; WebDAV PROPFIND/MKCOL
            // on a directory benefits from the trailing slash so the server
            // never returns a 301 redirect to the canonical form.
            return if (asCollection && !result.endsWith('/')) "$result/" else result
        }

        private fun appendSegments(
            builder: okhttp3.HttpUrl.Builder,
            raw: String,
        ) {
            raw
                .trim('/')
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach { segment ->
                    val clean = ServerStringValidation.cleanPathSegment(segment)
                        ?: throw IllegalStateException(
                            "Refusing to build WebDAV URL with hostile path segment",
                        )
                    builder.addPathSegment(clean)
                }
        }

        /**
         * B-40: strip RFC 7232 weak-validator prefix and surrounding quotes
         * so a server emitting weak ETags (`W/"abc"`) round-trips through
         * `If-Match` against the same value's strong form (`"abc"`) without
         * a phantom 412. Nextcloud's behind a mix of Apache configs in the
         * wild — some emit weak, some strong, sometimes for the same file.
         */
        private fun normaliseEtag(raw: String?): String? = raw?.removePrefix("W/")?.trim('"')

        private companion object {
            const val MARKDOWN = "text/markdown; charset=utf-8"
        }
    }
