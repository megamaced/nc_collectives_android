package com.megamaced.nccollectives.data.api

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

    data class Unexpected(
        val cause: Throwable,
    ) : ApiResult<Nothing>
}

internal inline fun <T> apiCall(block: () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        if (e.code() == 401) ApiResult.Unauthorised else ApiResult.HttpError(e.code(), e.message())
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    } catch (e: Exception) {
        ApiResult.Unexpected(e)
    }
