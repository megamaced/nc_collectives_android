package com.megamaced.nccollectives.integration

import okhttp3.mockwebserver.MockResponse

/**
 * Canned Nextcloud responses for [IntegrationEnvironment]'s [okhttp3.mockwebserver.MockWebServer].
 *
 * Hand-written JSON rather than serialised DTOs on purpose: a test that
 * builds its fixture with the same `@Serializable` classes the production
 * code parses cannot catch a field this app reads under a name the server
 * does not use. These strings are what the wire actually carries.
 */
internal object OcsResponses {
    /** `{ "ocs": { "meta": …, "data": <data> } }` — every Collectives reply. */
    fun envelope(data: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}""",
            )

    fun page(
        id: Long,
        title: String = "Page $id",
        parentId: Long = 0,
        fileName: String = "$title.md",
        filePath: String = "",
        collectivePath: String = IntegrationEnvironment.COLLECTIVE_PATH,
    ): String =
        """{"id":$id,"title":"$title","parentId":$parentId,"fileName":"$fileName",""" +
            """"filePath":"$filePath","collectivePath":"$collectivePath","timestamp":1700000000}"""

    /** `PUT /pages/{id}` and `POST /pages/{parentId}` — the `page` envelope. */
    fun singlePage(
        id: Long,
        title: String = "Page $id",
        parentId: Long = 0,
        fileName: String = "$title.md",
        filePath: String = "",
    ): MockResponse =
        envelope(
            """{"page":${page(id, title, parentId, fileName, filePath)}}""",
        )

    /** `GET /collectives/{id}/pages` — the `pages` envelope. */
    fun pageList(vararg pages: String): MockResponse = envelope("""{"pages":[${pages.joinToString(",")}]}""")

    /** `GET /collectives/{id}/tags`, which `refresh` fires alongside the page list. */
    fun emptyTagList(): MockResponse = envelope("""{"tags":[]}""")

    /** A bare WebDAV reply: no body, an optional ETag. */
    fun webDav(
        code: Int,
        etag: String? = null,
    ): MockResponse =
        MockResponse().setResponseCode(code).apply {
            if (etag != null) setHeader("ETag", etag)
        }
}
