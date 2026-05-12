package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LoginFlowInitResponse(
    val poll: PollEndpoint,
    val login: String,
)

@Serializable
data class PollEndpoint(
    val token: String,
    val endpoint: String,
)

@Serializable
data class LoginFlowResult(
    val server: String,
    val loginName: String,
    val appPassword: String,
)

sealed interface LoginFlowStatus {
    data object Polling : LoginFlowStatus

    data class Success(
        val result: LoginFlowResult,
    ) : LoginFlowStatus

    data class Error(
        val message: String,
    ) : LoginFlowStatus
}

@Singleton
class NextcloudLoginFlow
    @Inject
    constructor() {
        private val client = OkHttpClient()
        private val json = Json { ignoreUnknownKeys = true }

        fun initiate(host: String): Result<LoginFlowInitResponse> {
            val url = "${host.trimEnd('/')}/index.php/login/v2"
            val request = Request
                .Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .header("User-Agent", USER_AGENT)
                .build()

            return try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return Result.failure(
                        LoginFlowException("Server returned ${response.code}"),
                    )
                }
                val body = response.body?.string()
                    ?: return Result.failure(LoginFlowException("Empty response"))
                val initResponse = json.decodeFromString<LoginFlowInitResponse>(body)
                Result.success(initResponse)
            } catch (e: Exception) {
                Timber.e(e, "Login flow initiation failed")
                Result.failure(LoginFlowException("Failed to connect: ${e.message}", e))
            }
        }

        suspend fun poll(
            endpoint: String,
            token: String,
        ): LoginFlowStatus {
            val body = FormBody
                .Builder()
                .add("token", token)
                .build()
            val request = Request
                .Builder()
                .url(endpoint)
                .post(body)
                .header("User-Agent", USER_AGENT)
                .build()

            repeat(MAX_POLL_ATTEMPTS) {
                try {
                    val response = client.newCall(request).execute()
                    when (response.code) {
                        200 -> {
                            val responseBody = response.body?.string()
                                ?: return LoginFlowStatus.Error("Empty response")
                            val result = json.decodeFromString<LoginFlowResult>(responseBody)
                            return LoginFlowStatus.Success(result)
                        }
                        404 -> {
                            // Not yet authorised — keep polling.
                        }
                        else -> {
                            return LoginFlowStatus.Error("Server returned ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Poll attempt failed")
                }
                delay(POLL_INTERVAL_MS)
            }
            return LoginFlowStatus.Error("Login timed out")
        }

        companion object {
            private const val USER_AGENT = "NC Collectives Android"
            private const val POLL_INTERVAL_MS = 5_000L
            private const val MAX_POLL_ATTEMPTS = 60 // 5 minutes total
        }
    }

class LoginFlowException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
