package com.megamaced.nccollectives.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(
        val data: T,
    ) : ApiResult<T>

    data class HttpError(
        val code: Int,
        val message: String,
    ) : ApiResult<Nothing>

    data class NetworkError(
        val cause: Throwable,
    ) : ApiResult<Nothing>

    data object Unauthorised : ApiResult<Nothing>

    /** Server rejected an `If-Match`/`If-None-Match` precondition (412). */
    data object Conflict : ApiResult<Nothing>

    data class Unexpected(
        val cause: Throwable,
    ) : ApiResult<Nothing>
}

internal inline fun <T> apiCall(block: () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: CancellationException) {
        // B-45 / R-40: re-throw coroutine cancellation. The generic
        // `catch (Exception)` below would otherwise swallow it into
        // `ApiResult.Unexpected`, breaking structured concurrency (siblings
        // don't tear down) and surfacing a confusing "Unexpected error"
        // toast after the user navigates away from a screen.
        throw e
    } catch (e: HttpException) {
        when (e.code()) {
            401 -> ApiResult.Unauthorised
            412 -> ApiResult.Conflict
            else -> ApiResult.HttpError(e.code(), e.message())
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    } catch (e: SerializationException) {
        // B-49: deserialisation failures are network-shape problems (server
        // changed the contract, missing field, etc.) — surface them as
        // Unexpected with a typed cause rather than letting the generic
        // catch hide them.
        ApiResult.Unexpected(e)
    }

/**
 * Transform the [ApiResult.Success] payload while preserving every error arm
 * verbatim. Lets call sites collapse the otherwise-repetitive 6-arm `when`
 * that just remaps the data type. Named [mapSuccess] (not just `map`) so it
 * doesn't shadow `kotlinx.coroutines.flow.Flow.map`. The unchecked cast is
 * safe because every non-Success branch is `ApiResult<Nothing>`.
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <T, R> ApiResult<T>.mapSuccess(transform: (T) -> R): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> ApiResult.Success(transform(data))
        else -> this as ApiResult<R>
    }

/** Side-effect on success, propagate the original result. */
internal inline fun <T> ApiResult<T>.ifSuccess(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) block(data)
    return this
}

/**
 * R-21: chain a second [ApiResult]-returning call only on success of this
 * one. Error arms (`HttpError`, `NetworkError`, `Unauthorised`, `Conflict`,
 * `Unexpected`) short-circuit, so the call sites no longer need to spell
 * out a 6-arm `when` that returns each arm verbatim just to fan out one
 * downstream call. The unchecked cast is safe because every non-Success
 * branch is `ApiResult<Nothing>`.
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <T, R> ApiResult<T>.flatMapSuccess(transform: (T) -> ApiResult<R>): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> transform(data)
        else -> this as ApiResult<R>
    }
