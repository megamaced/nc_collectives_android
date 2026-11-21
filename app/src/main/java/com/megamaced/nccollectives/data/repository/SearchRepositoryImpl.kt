package com.megamaced.nccollectives.data.repository

import android.net.Uri
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.SearchApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.dto.SearchEntryDto
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.auth.bound
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.domain.model.SearchHit
import com.megamaced.nccollectives.domain.repository.SearchRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepositoryImpl
    @Inject
    constructor(
        private val api: SearchApiService,
        private val pageDao: PageDao,
        private val accountGeneration: AccountGeneration,
    ) : SearchRepository {
        override suspend fun search(
            term: String,
            limit: Int,
            onTitleHits: (List<SearchHit>) -> Unit,
        ): ApiResult<List<SearchHit>> {
            val trimmed = term.trim()
            if (trimmed.isEmpty()) return ApiResult.Success(emptyList())
            return accountGeneration.bound { generation ->
                val seen = mutableSetOf<String>()
                val titles = apiCall {
                    val response = api.searchPages(trimmed, limit).ocs
                    toHits(response.data.entries, seen)
                }.unlessAccountChanged(accountGeneration, generation)
                if (titles !is ApiResult.Success) return@bound titles

                currentCoroutineContext().ensureActive()
                onTitleHits(titles.data)

                // Older servers may not have the content provider. A failure here leaves the title hits usable.
                val content = apiCall {
                    val response = api.searchPageContent(trimmed, limit).body()?.ocs
                    if (response?.meta?.status == "ok") {
                        toHits(response.data.entries, seen, contentMatches = true)
                    } else {
                        emptyList()
                    }
                }
                val combined = if (content is ApiResult.Success) ApiResult.Success(titles.data + content.data) else titles
                combined.unlessAccountChanged(accountGeneration, generation)
            }
        }

        private suspend fun toHits(
            entries: List<SearchEntryDto>,
            seen: MutableSet<String>,
            contentMatches: Boolean = false,
        ): List<SearchHit> {
            val links = entries.map { entry ->
                val uri = entry.resourceUrl?.let(Uri::parse)?.takeIf { it.isHierarchical }
                val pageId = entry.attributes["fileId"]?.toLongOrNull() ?: uri?.pageId()
                // A landing link only names the collective. Match its exact cached route, never its title or id suffix.
                val landingRoute = if (pageId == null) uri?.collectivesRoute()?.singleOrNull() else null
                SearchLink(pageId, landingRoute)
            }
            val pages = links
                .mapNotNull { it.pageId }
                .distinct()
                .chunked(LOOKUP_BATCH_SIZE)
                .flatMap { pageDao.searchPagesById(it) }
                .associateBy { it.id }
            val landingPages = links
                .mapNotNull { it.landingRoute }
                .distinct()
                .chunked(LOOKUP_BATCH_SIZE)
                .flatMap { pageDao.searchLandingPages(it) }
                .groupBy { it.collectiveRoute }

            return entries.mapIndexedNotNull { index, entry ->
                val link = links[index]
                val page = link.pageId?.let { pages[it] } ?: link.landingRoute?.let { landingPages[it]?.singleOrNull()?.page }
                val pageId = link.pageId ?: page?.id
                val key = pageId?.let { "page:$it" } ?: link.landingRoute?.let { "landing:$it" }
                if (key != null && !seen.add(key)) return@mapIndexedNotNull null

                // The content provider sends the excerpt in title. Use the cached page title when we have it.
                val pageTitle = page?.takeIf { contentMatches }?.let { listOfNotNull(it.emoji, it.title).joinToString(" ") }
                val description = entry.subline?.takeIf { it.isNotBlank() }
                SearchHit(
                    title = pageTitle ?: entry.title,
                    snippet = if (pageTitle == null) description else entry.title.takeIf { it.isNotBlank() },
                    pageId = pageId,
                    collectiveId = page?.collectiveId,
                    context = description.takeIf { pageTitle != null },
                )
            }
        }

        private data class SearchLink(
            val pageId: Long?,
            val landingRoute: String?,
        )

        internal companion object {
            // A complete provider response can exceed the requested limit. Stay within SQLite's bind limit.
            private const val LOOKUP_BATCH_SIZE = 999

            /** A slugged page's link segment: `<slug>-<id>`. */
            private val SLUGGED_PAGE_SEGMENT = Regex("""-(\d+)$""")

            /**
             * The page id a Collectives search result's link names, or null.
             *
             * Collectives builds the link with `PageService::getPageLink`,
             * which has three shapes:
             *
             *  - a page without a slug:
             *    `…/apps/collectives/<collective>/<path…>/<title>?fileId=<id>`;
             *  - a page with one — every page created, renamed, moved or
             *    copied since Collectives 3.0 — `…/apps/collectives/<collective>/<slug>-<id>`,
             *    with no `fileId` and no folder path (issue #85: those
             *    results were all untappable);
             *  - an unslugged landing page: `…/apps/collectives/<collective>`,
             *    which names no page id at all.
             *
             * The slug shape is only read when it is exactly one segment
             * after the collective's, so a collective segment — which can
             * itself be `<slug>-<id>` — is never mistaken for a page.
             */
            fun pageIdFromResourceUrl(url: String): Long? = Uri.parse(url).takeIf { it.isHierarchical }?.pageId()

            private fun Uri.pageId(): Long? {
                // B-47: route the parse through `Uri.parse` so query lookup
                // handles fragments (`#anchor`) and percent-encoding correctly.
                // The previous `substringAfter('?') + split('&')` produced
                // `"42#anchor"` for `?fileId=42#anchor` and `toLongOrNull()`
                // returned null — the search hit became untappable.
                getQueryParameter("fileId")?.toLongOrNull()?.let { return it }
                val route = collectivesRoute() ?: return null
                if (route.size != 2) return null
                return SLUGGED_PAGE_SEGMENT
                    .find(route[1])
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            }

            private fun Uri.collectivesRoute(): List<String>? {
                val segments = pathSegments
                val appRoot = segments.indices.firstOrNull { i ->
                    segments[i] == "apps" && segments.getOrNull(i + 1) == "collectives"
                } ?: return null
                return segments.drop(appRoot + 2)
            }
        }
    }
