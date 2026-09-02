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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectEditingRepositoryImpl
    @Inject
    constructor(
        private val service: DirectEditingService,
        private val tokenStore: TokenStore,
    ) : DirectEditingRepository {
        /**
         * The last verdict, and the account it was about.
         *
         * Issue #22: this was a bare process-lifetime `Boolean?`, which two
         * things made wrong. The comment it carried — that the answer "only
         * changes when the admin installs / removes the Text app" — was true
         * when one account existed per process and stopped being true when
         * v2.10.0 let the user switch: account A's verdict was applied to
         * account B, silently downgrading B to the plain editor or routing it
         * into an editor its server may not support. And *every* non-success
         * was folded into `false` and then cached forever, so a single probe
         * made while offline disabled the collaborative editor until the
         * process restarted.
         *
         * `@Volatile` on an immutable holder is enough for readers: a caller
         * sees either the old reference or the new one, never a verdict
         * paired with the wrong account.
         */
        @Volatile private var cached: CachedCapability? = null
        private val capabilityLock = Mutex()

        override suspend fun isAvailable(): Boolean {
            // Signed out, or mid-switch. Nothing to key a verdict on, and
            // the probe would 401 anyway.
            val accountId = tokenStore.activeAccountId() ?: return false
            cached?.takeIf { it.accountId == accountId }?.let { return it.available }
            return capabilityLock.withLock {
                cached?.takeIf { it.accountId == accountId }?.let { return@withLock it.available }
                val result = apiCall { service.getCapability() }
                val available = result is ApiResult.Success && editorHandlesMarkdown(result.data.ocs.data)
                if (capabilityCacheability(result) == CapabilityCache.Decided) {
                    cached = CachedCapability(accountId = accountId, available = available)
                } else {
                    // We never got an answer, so don't record one. The
                    // native editor covers this run; the next call asks
                    // again.
                    Timber.i("Direct-editing capability probe was inconclusive; not caching it")
                }
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

/** One account's direct-editing verdict. See `DirectEditingRepositoryImpl.cached`. */
private data class CachedCapability(
    val accountId: String,
    val available: Boolean,
)

/** Whether a capability probe's outcome is worth remembering. */
internal enum class CapabilityCache {
    /** The server answered. The verdict holds until the account changes. */
    Decided,

    /** We never got an answer. Fall back for this call and ask again later. */
    Undecided,
}

/**
 * Issue #22: a network failure is not a server saying "I don't have Text".
 * Folding every non-success into a cached `false` meant one probe made while
 * offline — which, for an offline-first app, is the likely first probe —
 * disabled the collaborative editor for the rest of the process.
 *
 * A 404 is the one negative worth keeping: an older server, or one without
 * the Text app, genuinely has no `directEditing` endpoint, and that will not
 * change until an admin installs it. Everything else is the server declining
 * to tell us, including a 401 (the session is about to be re-established,
 * not permanently gone) and a 5xx.
 */
internal fun capabilityCacheability(result: ApiResult<*>): CapabilityCache =
    when (result) {
        is ApiResult.Success -> CapabilityCache.Decided

        is ApiResult.HttpError -> if (result.code == 404) CapabilityCache.Decided else CapabilityCache.Undecided

        is ApiResult.NetworkError,
        ApiResult.Unauthorised,
        ApiResult.Conflict,
        is ApiResult.Unexpected,
        -> CapabilityCache.Undecided
    }
