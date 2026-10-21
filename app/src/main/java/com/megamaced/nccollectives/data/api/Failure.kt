package com.megamaced.nccollectives.data.api

import androidx.annotation.StringRes
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.auth.AccountChangedException
import com.megamaced.nccollectives.util.UiText
import com.megamaced.nccollectives.util.uiText

/**
 * Why a call failed, in a form that can be kept and shown later, in
 * whatever language the app is in by then. [toUiText] words it; [encode]
 * and [decode] store it — as a stable name, never a resource id, which
 * changes between builds.
 */
sealed interface Failure {
    /** The server couldn't be reached. */
    data object Network : Failure

    /** The server answered with an error status. */
    data class Http(
        val code: Int,
    ) : Failure

    /** The server no longer accepts the session. */
    data object SessionExpired : Failure

    /** The page changed on the server since the version written against. */
    data object Conflict : Failure

    /** One of the app's own explanations. */
    data class App(
        val reason: AppFailure,
    ) : Failure

    /** Anything else, with what it said about itself, untranslated, when it said anything. */
    data class Unexpected(
        val detail: String?,
    ) : Failure

    /*
     * B-84: 403 used to render as the bare "Server returned 403", which is both
     * unhelpful and, for the members list, the *common* case rather than an
     * anomaly — Circles answers 403 whenever the signed-in user may not read a
     * team's membership.
     *
     * The wording stops short of "you are not a member" on purpose. Circles
     * returns the same 403 for a circle the user isn't in and for a circle that
     * doesn't exist, and declines to say which; asserting non-membership would
     * be a guess presented as fact. The same arm also covers the other 403s in
     * the app (edit rights revoked mid-session, a share withdrawn), which the
     * "or it may no longer exist" half describes just as well.
     *
     * It cannot do better than this, and the reason is worth recording: the
     * real explanation lives in the response body's `data.message` — `meta.message`
     * is empty on a 403 — but Retrofit throws `HttpException` on any non-2xx
     * before a converter runs, so [apiCall] only ever sees the status line.
     * Reading `data.message` would mean buffering and parsing
     * `HttpException.response()?.errorBody()` in `apiCall`, for every endpoint,
     * to surface a string written for web-app developers. Not worth it for a
     * case whose honest summary is already short.
     */
    fun toUiText(): UiText =
        when (this) {
            Network -> uiText(R.string.error_network)

            is Http -> if (code == 403) uiText(R.string.error_access_refused) else uiText(R.string.error_http, code)

            SessionExpired -> uiText(R.string.error_session_expired)

            Conflict -> uiText(R.string.error_conflict)

            is App -> reason.toUiText()

            is Unexpected -> detail?.takeIf { it.isNotBlank() }?.let { uiText(R.string.error_unexpected_detail, it) }
                ?: uiText(R.string.error_unexpected)
        }

    fun encode(): String =
        when (this) {
            Network -> "network"
            is Http -> "http:$code"
            SessionExpired -> "session-expired"
            Conflict -> "conflict"
            is App -> "app:${reason.name}"
            is Unexpected -> "unexpected:${detail.orEmpty()}"
        }

    companion object {
        /** What [encode] wrote, or null for anything it didn't — a newer build's reason, say. */
        fun decode(stored: String): Failure? {
            val kind = stored.substringBefore(':')
            val rest = stored.substringAfter(':', missingDelimiterValue = "")
            return when (kind) {
                "network" -> Network
                "http" -> rest.toIntOrNull()?.let(::Http)
                "session-expired" -> SessionExpired
                "conflict" -> Conflict
                "app" -> AppFailure.entries.firstOrNull { it.name == rest }?.let(::App)
                "unexpected" -> Unexpected(rest.ifEmpty { null })
                else -> null
            }
        }
    }
}

/**
 * The app's own reasons for not doing something, each worded for the
 * user. The names are stored (see [Failure.encode]), so they are renamed
 * only with a fallback for the old name.
 */
enum class AppFailure(
    @param:StringRes val text: Int,
) {
    /** What was asked for isn't in this device's copy: a page, its parent, a collective. */
    NotOnDevice(R.string.error_not_on_device),

    /** The account was switched before the work finished; see `AccountChangedException`. */
    AccountChanged(R.string.error_account_changed),
    AttachmentNotFound(R.string.error_attachment_not_found),
    AttachmentBeingDeleted(R.string.error_attachment_being_deleted),
    UploadSourceGone(R.string.error_upload_source_gone),

    /** A file name or path the app won't build a server address from. */
    UnsafePath(R.string.error_unsafe_path),

    /** There is no signed-in account to make the request as. */
    NoAccount(R.string.error_no_account),
    OtherCollective(R.string.error_other_collective),
    CrossCollectiveMove(R.string.error_cross_collective_move),
    LandingPage(R.string.error_landing_page),
    BlankName(R.string.error_blank_name),

    /** A page title with nothing left once what a file name can't hold is removed. */
    EmptyTitle(R.string.error_empty_title),

    /** A page title of `.` or `..`, which can't name a file. */
    ReservedTitle(R.string.error_reserved_title),
    NoMembersList(R.string.error_no_members_list),
    EmptyResponse(R.string.error_empty_response),
    ;

    fun toUiText(): UiText = uiText(text)
}

/**
 * Work the app refused, or couldn't do, for one of its own [reason]s: an
 * [ApiResult.Unexpected] carrying one is shown as the reason's words
 * rather than as an exception's.
 */
class AppFailureException(
    val reason: AppFailure,
) : IllegalStateException(reason.name)

/** Why this failed, or null when it didn't. */
fun ApiResult<*>.failure(): Failure? =
    when (this) {
        is ApiResult.Success<*> -> {
            null
        }

        is ApiResult.NetworkError -> {
            Failure.Network
        }

        is ApiResult.HttpError -> {
            Failure.Http(code)
        }

        is ApiResult.Unauthorised -> {
            Failure.SessionExpired
        }

        is ApiResult.Conflict -> {
            Failure.Conflict
        }

        is ApiResult.Unexpected -> {
            when (cause) {
                is AccountChangedException -> Failure.App(AppFailure.AccountChanged)
                is AppFailureException -> Failure.App(cause.reason)
                else -> Failure.Unexpected(cause.message)
            }
        }
    }

/** An [ApiResult.Unexpected] for one of the app's own [reason]s. */
internal fun appFailure(reason: AppFailure): ApiResult<Nothing> = ApiResult.Unexpected(AppFailureException(reason))
