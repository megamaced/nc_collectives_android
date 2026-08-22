package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.DirectEditingService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.auth.isSameServerHttpsUrl
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
        private val tokenStore: TokenStore,
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
            val expectedHost = tokenStore.getCredentials()?.host
                ?: return ApiResult.Unexpected(
                    IllegalStateException("No stored Nextcloud host; refusing to open an editor session"),
                )
            // `editorId = "text"` is the upstream id Text registers itself
            // under. Passing it explicitly avoids relying on the server's
            // default-editor-for-mimetype resolution, which depends on
            // installation order of editor apps.
            val result = apiCall { service.openSession(path = path, editorId = TEXT_EDITOR_ID) }
                .mapSuccess { it.ocs.data.url }
            if (result !is ApiResult.Success) return result
            return validatedSessionUrl(result.data, expectedHost)
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
        private fun editorHandlesMarkdown(
            capability: com.megamaced.nccollectives.data.api.dto.DirectEditingCapabilityEnvelopeData,
        ): Boolean =
            capability.editors.values.any { editor ->
                MARKDOWN_MIMES.any { mime ->
                    mime in editor.mimetypes || mime in editor.optionalMimetypes
                }
            }

        internal companion object {
            /**
             * S-22: gate the server-supplied session URL on scheme + host before
             * handing it to the editor WebView.
             *
             * `DirectEditingOpenEnvelopeData.url` is documented as a
             * fully-qualified URL on the user's own Nextcloud, and the WebView
             * that loads it is chromeless, JS-enabled, and has the
             * `DirectEditingMobileInterface` bridge bound — so an unvalidated
             * URL is a credential-phishing surface: a compromised or hostile
             * server names any host it likes, the user sees a full-screen page
             * with no address bar in exactly the context where a Nextcloud login
             * prompt looks plausible, and (before this check) the WebView's own
             * navigation allowlist was derived from that same URL, so it
             * self-adjusted to the attacker's host. Validating here makes the
             * DTO's assertion true rather than assumed, and anchors the
             * allowlist to the host we hold credentials for.
             *
             * Reuses [isSameServerHttpsUrl] (S-17's comparison) so the rule
             * can't drift from the one the login flow applies.
             */
            internal fun validatedSessionUrl(
                url: String,
                expectedHost: String,
            ): ApiResult<String> =
                if (isSameServerHttpsUrl(url, expectedHost)) {
                    ApiResult.Success(url)
                } else {
                    ApiResult.Unexpected(
                        IllegalStateException(
                            "Editor session URL is not an https URL on your Nextcloud host " +
                                "($expectedHost); refusing to open it",
                        ),
                    )
                }

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

            const val TEXT_EDITOR_ID = "text"
            val MARKDOWN_MIMES = listOf("text/markdown", "text/x-markdown")
        }
    }
