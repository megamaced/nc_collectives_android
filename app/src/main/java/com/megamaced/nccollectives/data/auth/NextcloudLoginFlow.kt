package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
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
        // S-17: explicit timeouts. Default OkHttpClient() has 10 s connect /
        // infinite read, which lets the login flow hang indefinitely on a
        // slow or hostile server. Login UX is happy to fail fast — the user
        // can retry.
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun initiate(host: String): Result<LoginFlowInitResponse> =
            withContext(Dispatchers.IO) {
                val url = "${host.trimEnd('/')}/index.php/login/v2"
                val request = Request
                    .Builder()
                    .url(url)
                    .post(FormBody.Builder().build())
                    .header("User-Agent", USER_AGENT)
                    .build()

                try {
                    // B-44: `.use { … }` releases the response body back to
                    // the connection pool even on a thrown decoder error.
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(
                                LoginFlowException("Server returned ${response.code}"),
                            )
                        }
                        val body = response.body?.string()
                            ?: return@withContext Result.failure(LoginFlowException("Empty response"))
                        val initResponse = json.decodeFromString<LoginFlowInitResponse>(body)
                        Result.success(initResponse)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Login flow initiation failed")
                    Result.failure(LoginFlowException("Failed to connect: ${e.message}", e))
                }
            }

        suspend fun poll(
            endpoint: String,
            token: String,
            expectedHost: String? = null,
        ): LoginFlowStatus =
            withContext(Dispatchers.IO) {
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
                    // Track the in-flight call so a thrown / cancelled iteration
                    // cancels the OkHttp exchange instead of leaving a blocking
                    // thread alive until the server times out (B-22). `cancel()`
                    // is a safe no-op once the call has completed.
                    val call = client.newCall(request)
                    try {
                        call.execute().use { response ->
                            when (response.code) {
                                200 -> {
                                    val responseBody = response.body?.string()
                                        ?: return@withContext LoginFlowStatus.Error("Empty response")
                                    val result = json.decodeFromString<LoginFlowResult>(responseBody)
                                    // S-17: the server-returned `server` field
                                    // becomes the canonical host for every
                                    // subsequent authenticated request. A MITM
                                    // or a misconfigured CDN can return a
                                    // different host than the user typed; the
                                    // app would then issue Basic-auth against
                                    // that host. Refuse the result if the host
                                    // doesn't match (or isn't a subdomain of)
                                    // what the user entered.
                                    if (expectedHost != null && !hostMatches(result.server, expectedHost)) {
                                        return@withContext LoginFlowStatus.Error(
                                            "Server returned a different host (${result.server}) " +
                                                "than the one you entered ($expectedHost). " +
                                                "Refusing to continue.",
                                        )
                                    }
                                    return@withContext LoginFlowStatus.Success(result)
                                }
                                404 -> {
                                    // Not yet authorised — keep polling.
                                }
                                else -> {
                                    return@withContext LoginFlowStatus.Error("Server returned ${response.code}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Poll attempt failed")
                    } finally {
                        call.cancel()
                    }
                    delay(POLL_INTERVAL_MS)
                }
                LoginFlowStatus.Error("Login timed out")
            }

        private fun hostMatches(
            returned: String,
            expected: String,
        ): Boolean {
            val returnedHost = returned.toHttpUrlOrNull()?.host ?: return false
            val expectedHost = expected.toHttpUrlOrNull()?.host
                ?: ("https://${expected.trimEnd('/')}").toHttpUrlOrNull()?.host
                ?: return false
            // Allow exact match or subdomain (e.g. user typed `nextcloud.example.com`
            // and server returned `files.nextcloud.example.com`).
            return returnedHost.equals(expectedHost, ignoreCase = true) ||
                returnedHost.endsWith(".$expectedHost", ignoreCase = true)
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
