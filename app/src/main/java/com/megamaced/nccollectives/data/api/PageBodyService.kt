package com.megamaced.nccollectives.data.api

import android.util.Xml
import com.megamaced.nccollectives.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** A page body along with the WebDAV ETag the server returned for it. */
data class PageBody(
    val markdown: String,
    val etag: String?,
)

/** One entry parsed out of a WebDAV multistatus response. */
data class WebDavEntry(
    val href: String,
    val displayName: String,
    val contentType: String?,
    val size: Long,
    val lastModifiedMs: Long,
    val etag: String?,
    val isCollection: Boolean,
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
         * Lists the contents of a WebDAV collection (directory) one level
         * deep via `PROPFIND Depth: 1`. The collection itself is filtered
         * out — callers only want children.
         *
         * A 404 is treated as an empty list (the directory simply hasn't
         * been created yet, which is the common case for fresh pages).
         */
        suspend fun propfind(
            collectivePath: String,
            filePath: String,
            directoryName: String,
        ): ApiResult<List<WebDavEntry>> =
            withContext(Dispatchers.IO) {
                try {
                    val url = buildWebDavUrl(collectivePath, filePath, directoryName, asCollection = true)
                    val request = Request
                        .Builder()
                        .url(url)
                        .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML.toMediaType()))
                        .header("Depth", "1")
                        .build()
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code == 207 -> {
                                val xml = response.body?.string().orEmpty()
                                val all = parsePropfind(xml)
                                ApiResult.Success(
                                    // The collection itself is always the first
                                    // entry; strip it by matching the request URL.
                                    all.filterNot { it.isCollection && it.matchesRequestPath(url) },
                                )
                            }
                            response.code == 404 -> ApiResult.Success(emptyList())
                            response.code == 401 -> ApiResult.Unauthorised
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

        /** DELETEs a WebDAV resource (file or empty directory). */
        suspend fun deleteFile(
            collectivePath: String,
            filePath: String,
            fileName: String,
        ): ApiResult<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val url = buildWebDavUrl(collectivePath, filePath, fileName)
                    val request = Request
                        .Builder()
                        .url(url)
                        .delete()
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            in 200..299, 404 -> ApiResult.Success(Unit)
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

        private fun parsePropfind(xml: String): List<WebDavEntry> {
            if (xml.isBlank()) return emptyList()
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.reader())

            val results = mutableListOf<WebDavEntry>()
            var event = parser.eventType
            var inResponse = false
            var href: String? = null
            var contentType: String? = null
            var size: Long = 0
            var lastModified: Long = 0L
            var etag: String? = null
            var isCollection = false
            val davNs = "DAV:"

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.namespace == davNs) {
                            when (parser.name) {
                                "response" -> {
                                    inResponse = true
                                    href = null
                                    contentType = null
                                    size = 0
                                    lastModified = 0L
                                    etag = null
                                    isCollection = false
                                }
                                "href" -> if (inResponse) href = parser.nextText().trim()
                                "getcontenttype" -> if (inResponse) contentType = parser.nextText().trim()
                                "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: 0L
                                "getlastmodified" -> if (inResponse) {
                                    lastModified = parseHttpDate(parser.nextText().trim())
                                }
                                "getetag" -> if (inResponse) etag = parser.nextText().trim().trim('"')
                                "collection" -> if (inResponse) isCollection = true
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.namespace == davNs && parser.name == "response" && href != null) {
                            inResponse = false
                            val display = displayNameFromHref(href!!)
                            results.add(
                                WebDavEntry(
                                    href = href!!,
                                    displayName = display,
                                    contentType = contentType,
                                    size = size,
                                    lastModifiedMs = lastModified,
                                    etag = etag,
                                    isCollection = isCollection,
                                ),
                            )
                        }
                    }
                }
                event = parser.next()
            }
            return results
        }

        private fun displayNameFromHref(href: String): String {
            val trimmed = href.trimEnd('/')
            val last = trimmed.substringAfterLast('/', "")
            return try {
                URLDecoder.decode(last, "UTF-8")
            } catch (_: IllegalArgumentException) {
                last
            }
        }

        private fun parseHttpDate(value: String): Long =
            if (value.isBlank()) {
                0L
            } else {
                try {
                    HTTP_DATE_FORMAT.get()?.parse(value)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }

        /** Matches a WebDAV response href against the URL we requested. */
        private fun WebDavEntry.matchesRequestPath(requestUrl: String): Boolean {
            val responsePath = href.trimEnd('/')
            val requestedPath = requestUrl
                .toHttpUrlOrNull()
                ?.encodedPath
                ?.trimEnd('/')
                ?: return false
            return responsePath.endsWith(requestedPath)
        }

        private companion object {
            const val MARKDOWN = "text/markdown; charset=utf-8"
            const val XML = "application/xml; charset=utf-8"
            const val PROPFIND_BODY =
                """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontenttype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:getetag/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""

            // RFC 7231 §7.1.1.1 imf-fixdate is the canonical WebDAV form for
            // `getlastmodified`. ThreadLocal keeps SimpleDateFormat thread-safe.
            val HTTP_DATE_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat =
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
            }
        }
    }
