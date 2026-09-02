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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A page body along with the WebDAV ETag the server returned for it. */
data class PageBody(
    val markdown: String,
    val etag: String?,
)

/**
 * Outcome of a conditional body fetch ([PageBodyService.fetchBodyIfChanged]).
 *
 * [NotModified] is the common case on a revalidation — the server answered
 * `304` and sent no body, which is what makes "check for changes every time
 * a page is opened" affordable on mobile data.
 */
sealed interface ConditionalBody {
    data object NotModified : ConditionalBody

    data class Modified(
        val body: PageBody,
    ) : ConditionalBody
}

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
            val url = buildWebDavUrl(collectivePath, filePath, fileName) ?: return unbuildableUrl()
            val request = Request
                .Builder()
                .url(url)
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
         * Revalidating variant of [fetchBody]. Sends [knownEtag] as
         * `If-None-Match`, so an unchanged page costs a bare `304` with no
         * body rather than a full re-download (B-58).
         *
         * The quoting mirrors [saveBody]'s `If-Match`: [normaliseEtag]
         * stripped the quotes and any weak-validator prefix on the way in,
         * so they go back on here. A server that answers a normalised strong
         * form with `W/"…"` still matches — RFC 7232 mandates the *weak*
         * comparison function for `If-None-Match`, which ignores the prefix.
         */
        suspend fun fetchBodyIfChanged(
            collectivePath: String,
            filePath: String,
            fileName: String,
            knownEtag: String,
        ): ApiResult<ConditionalBody> {
            val url = buildWebDavUrl(collectivePath, filePath, fileName) ?: return unbuildableUrl()
            val request = Request
                .Builder()
                .url(url)
                .header("If-None-Match", "\"$knownEtag\"")
                .get()
                .build()
            return webDavCall(request, extraSuccessCodes = setOf(304)) { response ->
                if (response.code == 304) {
                    ConditionalBody.NotModified
                } else {
                    ConditionalBody.Modified(
                        PageBody(
                            markdown = response.body?.string().orEmpty(),
                            etag = normaliseEtag(response.header("ETag")),
                        ),
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
        ): ApiResult<String?> {
            val url = buildWebDavUrl(collectivePath, filePath, fileName) ?: return unbuildableUrl()
            val builder = Request
                .Builder()
                .url(url)
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
            val url = buildWebDavUrl(collectivePath, filePath, directoryName, asCollection = true)
                ?: return unbuildableUrl()
            val request = Request
                .Builder()
                .url(url)
                .method("MKCOL", null)
                .build()
            return webDavCall(request, extraSuccessCodes = setOf(405)) { }
        }

        /**
         * Creates a binary file at `(collectivePath, filePath, fileName)`.
         *
         * `If-None-Match: *` makes it a *create*, not a write: the server
         * refuses with 412 — which [webDavCall] folds into
         * [ApiResult.Conflict] — if anything is already at that path.
         *
         * Issue #24: this used to be an unconditional PUT, with the
         * disambiguation left entirely to the caller's
         * `resolveCollisionFreeName`, which probes only the *local*
         * attachments table. So a name that looked free locally because the
         * cache was stale, incomplete, or predated another client's upload
         * was written over. Note the asymmetry it leaves behind: page bodies
         * have guarded writes with `If-Match` and revalidated reads with
         * `If-None-Match` since the beginning, and attachment uploads carried
         * no precondition at all. Only the server can settle this — a
         * pre-upload refresh narrows the window and cannot close it.
         */
        suspend fun uploadFile(
            collectivePath: String,
            filePath: String,
            fileName: String,
            body: RequestBody,
        ): ApiResult<String?> {
            val url = buildWebDavUrl(collectivePath, filePath, fileName) ?: return unbuildableUrl()
            val request = Request
                .Builder()
                .url(url)
                .header("If-None-Match", "*")
                .put(body)
                .build()
            return webDavCall(request) { response -> normaliseEtag(response.header("ETag")) }
        }

        /**
         * Removes the file at `(collectivePath, filePath, fileName)`.
         *
         * Issue #35: used to undo an upload the user cancelled while its PUT
         * was already on the wire. `404` counts as success — the object not
         * being there is the outcome asked for, and the tombstone that drives
         * this is retried, so a second attempt against an already-deleted
         * file must settle rather than loop.
         *
         * WebDAV rather than the OCS `deleteAttachment` endpoint because that
         * one needs the server-assigned attachment id, which only arrives on
         * the next listing — and the whole point here is to remove the object
         * before any listing sees it.
         */
        suspend fun deleteFile(
            collectivePath: String,
            filePath: String,
            fileName: String,
        ): ApiResult<Unit> {
            val url = buildWebDavUrl(collectivePath, filePath, fileName) ?: return unbuildableUrl()
            val request = Request
                .Builder()
                .url(url)
                .delete()
                .build()
            return webDavCall(request, extraSuccessCodes = setOf(404)) { }
        }

        /**
         * Streams the file at `(collectivePath, filePath, fileName)` into
         * [target], returning the `Content-Type` the server reported (which
         * is more trustworthy than guessing from the extension). Used to
         * stage a non-image attachment in the app cache before handing it to
         * another app via `ACTION_VIEW`.
         *
         * Streamed rather than buffered: attachments are arbitrary user
         * files, and a multi-MB PDF read into a `ByteArray` first would be a
         * needless allocation spike on the exact devices least able to
         * absorb one. Partial writes are cleaned up by the caller.
         */
        suspend fun downloadTo(
            collectivePath: String,
            filePath: String,
            fileName: String,
            target: java.io.File,
        ): ApiResult<String?> {
            val url = buildWebDavUrl(collectivePath, filePath, fileName) ?: return unbuildableUrl()
            val request = Request
                .Builder()
                .url(url)
                .get()
                .build()
            return webDavCall(request) { response ->
                response.body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                response
                    .header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
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
                            response.code in 200..299 || response.code in extraSuccessCodes -> {
                                ApiResult.Success(onSuccess(response))
                            }

                            response.code == 401 -> {
                                ApiResult.Unauthorised
                            }

                            response.code == 412 -> {
                                ApiResult.Conflict
                            }

                            else -> {
                                ApiResult.HttpError(response.code, response.message)
                            }
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
        ): String =
            buildWebDavUrl(collectivePath, filePath, fileName)
                // Keeps the throwing contract, unlike the `ApiResult`
                // entry points above: this returns a bare `String` for Coil
                // and `AttachmentRepositoryImpl.remoteUrlFor` already
                // try/catches it into "no remote url". The other two callers
                // there (`urlFor`, `attachmentsBaseUrl`) return `String?`
                // and would be the natural place to absorb it too.
                ?: throw IllegalStateException("Refusing to build a WebDAV URL from unvalidated inputs")

        /**
         * B-68: every WebDAV entry point maps an unbuildable URL to this
         * instead of throwing.
         *
         * The URL is assembled from server-supplied strings and the stored
         * credentials, so it can legitimately fail: signed out mid-call, a
         * stored host that no longer parses, or a `PageDto.fileName` that
         * fails S-14′ validation (it defaults to `""`, and
         * `cleanPathSegment("")` returns null — so this is reachable from
         * ordinary bad data, not just a hostile server). Because every call
         * site built its `Request` *before* [webDavCall]'s try block, the
         * previous `IllegalStateException` sailed past all of the
         * `ApiResult` error handling and out through
         * `PageRepositoryImpl.fetchBody`/`refreshBodyIfChanged`/`saveBody`,
         * neither of which wraps in `apiCall` — i.e. straight into
         * `viewModelScope` and a process crash. Same posture as
         * `AttachmentRepositoryImpl.remoteUrlFor` and
         * `DirectEditingRepositoryImpl.serverPathFor`, which already treat
         * this failure as data rather than an exception.
         */
        private fun unbuildableUrl(): ApiResult<Nothing> =
            ApiResult.Unexpected(
                IllegalStateException("Couldn't build the WebDAV URL: no credentials, or a rejected path segment"),
            )

        /** Null when the URL can't be built — see [unbuildableUrl]. */
        private fun buildWebDavUrl(
            collectivePath: String,
            filePath: String,
            fileName: String,
            asCollection: Boolean = false,
        ): String? {
            val credentials = tokenStore.getCredentials() ?: return null
            val base = credentials.host.toHttpUrlOrNull() ?: return null
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
            if (!appendSegments(builder, collectivePath)) return null
            if (filePath.isNotEmpty() && !appendSegments(builder, filePath)) return null
            val finalSegment = ServerStringValidation.cleanPathSegment(fileName) ?: return null
            builder.addPathSegment(finalSegment)
            val result = builder.build().toString()
            // OkHttp's HttpUrl trims trailing slashes; WebDAV PROPFIND/MKCOL
            // on a directory benefits from the trailing slash so the server
            // never returns a 301 redirect to the canonical form.
            return if (asCollection && !result.endsWith('/')) "$result/" else result
        }

        /** False when any segment of [raw] fails validation; [builder] is then unusable. */
        private fun appendSegments(
            builder: okhttp3.HttpUrl.Builder,
            raw: String,
        ): Boolean {
            raw
                .trim('/')
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach { segment ->
                    val clean = ServerStringValidation.cleanPathSegment(segment) ?: return false
                    builder.addPathSegment(clean)
                }
            return true
        }

        /**
         * B-40: strip the RFC 9110 weak-validator prefix and surrounding
         * quotes, so what is stored is the opaque tag and every header this
         * class writes re-quotes it as a strong validator.
         *
         * Issue #34 argues this is invalid validator handling, and on the
         * letter of the RFC it is: `W/"abc"` and `"abc"` are unequal under
         * the strong comparison `If-Match` requires, so an origin whose *own*
         * ETag is weak must reject `If-Match: "abc"`. Deliberately kept
         * anyway, because the case B-40 was written for is the other one —
         * a proxy weakening the header on the way out while the origin's
         * stored ETag stays strong, which is what "a mix of Apache configs in
         * the wild, some emitting weak, some strong, sometimes for the same
         * file" meant. There, stripping is what makes the save work, and
         * sending `W/"abc"` verbatim would 412 every time.
         *
         * The client cannot tell the two apart from a response header. And
         * against an origin that really holds a weak validator, neither form
         * can strong-compare, so no `If-Match` value saves: the choice is
         * this 412 — which routes into the conflict branch, keeps the user's
         * text as a draft and offers "Replace with my draft" — or a blind
         * write with no precondition, which B-61 refuses. Closing it properly
         * means the queue row remembering the body the edit was based on, the
         * same schema change B-61 identified.
         *
         * What is worth doing is not hiding it: a weak validator is logged,
         * so "every save on my server conflicts" is diagnosable from a log
         * rather than only from a packet capture.
         */
        private fun normaliseEtag(raw: String?): String? {
            if (raw != null && raw.trimStart().startsWith("W/")) {
                Timber.d(
                    "Server sent a weak ETag (%s); If-Match cannot guarantee lost-update prevention against it",
                    raw,
                )
            }
            return raw?.removePrefix("W/")?.trim('"')
        }

        private companion object {
            const val MARKDOWN = "text/markdown; charset=utf-8"
        }
    }
