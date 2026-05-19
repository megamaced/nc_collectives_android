package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.DirectEditingService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectEditingRepositoryImpl
    @Inject
    constructor(
        private val service: DirectEditingService,
    ) : DirectEditingRepository {
        // Process-lifetime memoisation. The capability response only
        // changes when the admin installs / removes the Text app, which
        // we don't need to react to mid-session. `Mutex` so concurrent
        // first-touches don't race on the network call.
        @Volatile private var cachedAvailable: Boolean? = null
        private val capabilityLock = Mutex()

        override suspend fun isAvailable(): Boolean {
            cachedAvailable?.let { return it }
            return capabilityLock.withLock {
                cachedAvailable?.let { return@withLock it }
                val result = apiCall { service.getCapability() }
                val available = when (result) {
                    is ApiResult.Success -> editorHandlesMarkdown(result.data.ocs.data)
                    // Treat any non-success — including 404 on older servers
                    // that don't expose the endpoint — as "not available".
                    // The native editor is the fallback.
                    else -> false
                }
                cachedAvailable = available
                available
            }
        }

        override suspend fun openSession(page: Page): ApiResult<String> {
            val path = serverPathFor(page)
                ?: return ApiResult.Unexpected(
                    IllegalStateException(
                        "Page ${page.id} has a path segment that failed validation; refusing to open session",
                    ),
                )
            // `editorId = "text"` is the upstream id Text registers itself
            // under. Passing it explicitly avoids relying on the server's
            // default-editor-for-mimetype resolution, which depends on
            // installation order of editor apps.
            return apiCall { service.openSession(path = path, editorId = TEXT_EDITOR_ID) }
                .mapSuccess { it.ocs.data.url }
        }

        /**
         * Build the server-relative path the `directEditing/open` endpoint
         * expects: e.g. `.Collectives/Wiki/Some Folder/Page.md`.
         *
         * Reuses [ServerStringValidation.cleanPathSegment] (S-14′) so a
         * compromised server feeding us `..` segments via PageDto can't
         * route the open request anywhere we didn't intend. Returns
         * `null` on validation failure — the caller surfaces an error.
         */
        internal fun serverPathFor(page: Page): String? {
            val parts = buildList {
                addAll(
                    page.collectivePath
                        .trim('/')
                        .split('/')
                        .filter { it.isNotEmpty() },
                )
                if (page.filePath.isNotEmpty()) {
                    addAll(
                        page.filePath
                            .trim('/')
                            .split('/')
                            .filter { it.isNotEmpty() },
                    )
                }
                add(page.fileName)
            }
            val cleaned = parts.map { ServerStringValidation.cleanPathSegment(it) ?: return null }
            return cleaned.joinToString("/")
        }

        private fun editorHandlesMarkdown(
            capability: com.megamaced.nccollectives.data.api.dto.DirectEditingCapabilityEnvelopeData,
        ): Boolean =
            capability.editors.values.any { editor ->
                MARKDOWN_MIMES.any { mime ->
                    mime in editor.mimetypes || mime in editor.optionalMimetypes
                }
            }

        private companion object {
            const val TEXT_EDITOR_ID = "text"
            val MARKDOWN_MIMES = listOf("text/markdown", "text/x-markdown")
        }
    }
