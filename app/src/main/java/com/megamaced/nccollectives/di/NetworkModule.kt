package com.megamaced.nccollectives.di

import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.data.api.AuthInterceptor
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.GitHubReleaseService
import com.megamaced.nccollectives.data.api.HostInterceptor
import com.megamaced.nccollectives.data.api.SearchApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    // Retrofit needs a static base URL at construction time. HostInterceptor
    // rewrites scheme/host/port on every request to the user's Nextcloud
    // instance loaded from TokenStore.
    private const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        hostInterceptor: HostInterceptor,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            // Explicit timeouts — OkHttp's defaults are 10 s connect / no
            // read-write timeout, which leaves the UI scope hanging on a
            // slow Nextcloud. Attachment uploads disable the call timeout
            // separately at their request site so multi-MB images can
            // stream over slow links without hitting it (B-11).
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Keep TLS handshakes amortised across the rapid PROPFIND +
            // GET + ETag refresh sequence on a single page view (R-12).
            .connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES))
            .addInterceptor(hostInterceptor)
            .addInterceptor(authInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            // BASIC only: never log headers/body — the Basic-auth
                            // token would otherwise reach logcat.
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }.build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit
            .Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideCollectivesApi(retrofit: Retrofit): CollectivesApiService = retrofit.create(CollectivesApiService::class.java)

    @Provides
    @Singleton
    fun provideSearchApi(retrofit: Retrofit): SearchApiService = retrofit.create(SearchApiService::class.java)

    /**
     * Separate OkHttp / Retrofit pair for the GitHub Releases API used by
     * the in-app update check. Crucially **not** routed through the shared
     * `OkHttpClient` because that pipeline carries [HostInterceptor] (which
     * rewrites the request URL to the user's Nextcloud host) and
     * [AuthInterceptor] (which attaches Basic-auth credentials) — neither
     * of which is appropriate for a third-party API request.
     */
    @Provides
    @Singleton
    fun provideGitHubReleaseService(json: Json): GitHubReleaseService {
        val client = OkHttpClient.Builder().build()
        val contentType = "application/json".toMediaType()
        return Retrofit
            .Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GitHubReleaseService::class.java)
    }
}
