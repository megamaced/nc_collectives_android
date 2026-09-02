package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.auth.TokenStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites every outgoing request's scheme/host/port/path-prefix to point at
 * the user's Nextcloud instance from [TokenStore]. Retrofit needs a static
 * base URL at construction time, so we use a placeholder and override at
 * request time.
 *
 * **B-12**: the host stored in [TokenStore] may include a subdirectory
 * prefix (e.g. `https://example.com/nextcloud`); without preserving that
 * prefix every OCS / WebDAV call hits the bare domain and 404s. We now
 * concatenate the stored URL's `encodedPath` in front of the request's
 * own path before forwarding.
 *
 * **S-23**: only two URL shapes are legitimate here — Retrofit's
 * `placeholder.invalid` base and a URL already built from the stored host
 * ([PageBodyService.resourceUrl]). Anything else arrived as *content*: a
 * page body is shared, so a co-member with write access can plant
 * `![](https://anything/index.php/apps/x/y?z)` and Markwon hands that
 * absolute URL straight to this client. Retargeting it would preserve the
 * attacker's path and query while swapping in the victim's own host, and
 * [AuthInterceptor] would then attach Basic-auth to it — a blind
 * request-forgery primitive against the victim's server. Such requests are
 * refused outright rather than retargeted, and every request this
 * interceptor does vouch for carries a [RequestOrigin] tag that
 * [AuthInterceptor] requires before it will attach credentials.
 *
 * **Issue #21**: S-23 stopped page content nominating an arbitrary *host*
 * and stopped short of an arbitrary *path* on the known one. A ref already
 * pointing at the user's own Nextcloud passed [originOf]'s host-equality
 * test, was tagged `StoredHost`, and got signed — so any co-member with
 * write access could make the app issue an authenticated GET to a path of
 * their choosing, complete with the `OCS-APIRequest` header OCS endpoints
 * require, merely by the victim opening the page. Provenance is a path
 * question now, not a hostname one: see [isAppBuiltPath].
 */
@Singleton
class HostInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val credentials = tokenStore.getCredentials() ?: return chain.proceed(chain.request())
            val target = credentials.host.toHttpUrlOrNull() ?: return chain.proceed(chain.request())
            // S-21: defence-in-depth HTTPS enforcement. LoginViewModel
            // already rejects `http://` at sign-in and the network-security
            // config denies cleartext at platform level, but a future code
            // path that writes credentials directly (debug toggle,
            // import-from-other-app) shouldn't be able to silently downgrade
            // every authenticated request to cleartext. Failing the call
            // here makes the regression loud rather than a slow leak.
            if (target.scheme != "https") {
                throw IOException("Refusing non-https Nextcloud host: ${target.scheme}")
            }

            val original = chain.request()
            if (!isKnownHost(original.url, target)) {
                throw IOException(
                    "Refusing to send a request to an unrecognised host (${original.url.host}); " +
                        "only the app's own base URL and the stored Nextcloud host are routed here",
                )
            }
            val builder = original
                .newBuilder()
                .url(retarget(original.url, target))
            // Issue #21: a URL on the stored host that this app didn't build
            // is forwarded *untagged*, not refused. A genuinely public image
            // on the user's own instance should still render; what it must
            // not get is credentials, and the missing tag is what stops
            // `AuthInterceptor` attaching them.
            originOf(original.url, target)?.let { builder.tag(RequestOrigin::class.java, it) }
            return chain.proceed(builder.build())
        }

        internal companion object {
            /**
             * Whether this app talks to [original]'s host at all. A host that
             * is neither the stored Nextcloud nor Retrofit's placeholder can
             * only have arrived as content, and retargeting it would preserve
             * the content author's path and query while swapping in the
             * victim's host — so it is refused rather than forwarded (S-23).
             */
            fun isKnownHost(
                original: HttpUrl,
                target: HttpUrl,
            ): Boolean =
                original.host.equals(target.host, ignoreCase = true) ||
                    original.host.equals(PLACEHOLDER_HOST, ignoreCase = true)

            /**
             * Classify [original]'s provenance, or `null` when it has none we
             * recognise — see the S-23 note on the class.
             *
             * A URL on [PLACEHOLDER_HOST] was built by Retrofit from our own
             * service interfaces, so it is app-built by construction; that
             * covers every OCS call and the avatar route, which
             * `memberAvatarUrl` also builds on the placeholder host.
             *
             * A URL already on the target host is a weaker claim. It was
             * *probably* built from the stored credential by
             * [PageBodyService], but it may equally have arrived as content:
             * a page body is shared, and a co-member can plant an absolute
             * ref at the user's own server. So the path has to earn the tag
             * — [isAppBuiltPath].
             *
             * A same-host URL that doesn't is still forwarded, because a
             * genuinely public image on the user's instance should render;
             * it is forwarded *untagged*, which is what stops
             * [AuthInterceptor] signing it (issue #21).
             */
            fun originOf(
                original: HttpUrl,
                target: HttpUrl,
            ): RequestOrigin? =
                when {
                    original.host.equals(PLACEHOLDER_HOST, ignoreCase = true) -> RequestOrigin.AppBaseUrl
                    !original.host.equals(target.host, ignoreCase = true) -> null
                    isAppBuiltPath(original, target) -> RequestOrigin.StoredHost
                    else -> null
                }

            /**
             * Whether a URL already on the stored host sits on a path this
             * app is the one that builds.
             *
             * WebDAV is the entire list. [PageBodyService.resourceUrl] is the
             * only thing in the app that constructs an absolute URL on the
             * stored host, and everything it builds is
             * `<prefix>/remote.php/dav/files/<login>/…` — page bodies,
             * attachment uploads, and the attachment URLs handed to Coil and
             * to Markwon. Every other authenticated call is a Retrofit one
             * and arrives on [PLACEHOLDER_HOST] instead, so nothing
             * legitimate is lost by refusing to vouch for a same-host `/ocs/`
             * or `/index.php/apps/…` path — and those are precisely the
             * side-effecting endpoints a planted image ref would want
             * (issue #21).
             *
             * A read-only WebDAV GET is a much smaller thing to be tricked
             * into than an OCS call, but it is not nothing, which is why
             * `absolutizeImageRefs` independently confines an inline ref to
             * the page's own directory. This check is the backstop for a
             * fetch that reaches the client some other way.
             */
            fun isAppBuiltPath(
                original: HttpUrl,
                target: HttpUrl,
            ): Boolean {
                val prefix = target.encodedPath.trimEnd('/')
                return original.encodedPath.startsWith("$prefix$WEBDAV_PATH_PREFIX")
            }

            /**
             * Point [original] at [target]'s scheme/host/port, splicing in
             * [target]'s subdirectory prefix only when [original] doesn't
             * already carry it.
             *
             * **B-60**: two kinds of URL reach this interceptor. Retrofit's
             * are built on the `placeholder.invalid` base and carry a bare
             * path (`/ocs/v2.php/...`), so the prefix has to be spliced in.
             * The rest — WebDAV page bodies and the attachment URLs handed
             * to Coil — are built from the stored host by
             * [PageBodyService.resourceUrl] and already have it. Prefixing
             * those a second time turned every body fetch on a
             * `https://example.com/nextcloud` install into
             * `/nextcloud/nextcloud/remote.php/dav/...`, so pages listed
             * fine (OCS, correctly prefixed) but opening any one of them
             * answered 404 (GH-8). The host is what tells the two apart: a
             * URL already on the target host was built from the stored URL,
             * prefix included.
             */
            fun retarget(
                original: HttpUrl,
                target: HttpUrl,
            ): HttpUrl {
                val prefix = target.encodedPath.trimEnd('/')
                val alreadyOnTargetHost = original.host == target.host
                return original
                    .newBuilder()
                    .scheme(target.scheme)
                    .host(target.host)
                    .port(target.port)
                    .apply {
                        if (prefix.isNotEmpty() && !alreadyOnTargetHost) {
                            // OkHttp's encodedPath setter expects a leading '/'.
                            encodedPath(prefix + original.encodedPath)
                        }
                    }.build()
            }

            /**
             * Host half of Retrofit's base URL. `NetworkModule` builds
             * `PLACEHOLDER_BASE_URL` from this constant rather than
             * repeating the literal, so the base URL and this
             * security-relevant check can't drift apart.
             */
            const val PLACEHOLDER_HOST = "placeholder.invalid"

            /**
             * What [PageBodyService.resourceUrl] puts after the stored host's
             * own subdirectory prefix. Kept here rather than in
             * `PageBodyService` because this is the security-relevant use of
             * it; the two are checked against each other by
             * `HostInterceptorTest`.
             */
            private const val WEBDAV_PATH_PREFIX = "/remote.php/dav/files/"
        }
    }

/**
 * Provenance of an outgoing request's URL, stamped by [HostInterceptor] and
 * required by [AuthInterceptor] before credentials are attached (S-23).
 *
 * Both values are equally trusted — the tag's job is to distinguish "a URL
 * this app constructed" from "a URL that arrived in a server response",
 * which carries no tag at all because [HostInterceptor] refuses it.
 */
internal enum class RequestOrigin {
    /** Built by Retrofit on the placeholder base URL. */
    AppBaseUrl,

    /** Built from the stored Nextcloud host — WebDAV bodies, attachments. */
    StoredHost,
}
