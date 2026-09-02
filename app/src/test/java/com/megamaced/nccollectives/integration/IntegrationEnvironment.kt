package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.megamaced.nccollectives.data.api.AuthInterceptor
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.HostInterceptor
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.StoredCredentials
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import com.megamaced.nccollectives.data.repository.PageRepositoryImpl
import com.megamaced.nccollectives.di.NetworkModule
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.sync.SyncScheduler
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File

/**
 * Everything an integration test needs to exercise a real code path: a real
 * Room database, the real OkHttp interceptor chain, real Retrofit service
 * proxies, and the real repositories built over them, with a
 * [MockWebServer] standing in for Nextcloud.
 *
 * Why this exists, and why it is shaped like this:
 *
 * Two rounds of audits found bugs almost exclusively in *seams* — a response
 * landing after an account switch, an upload's 412 racing the body that
 * references it, a queue row settled by one path and read by another. None of
 * those are reachable from a pure function, so the suite could only ever pin
 * the decision points and leave the wiring between them untested. This puts
 * the wiring under test.
 *
 * It runs under Robolectric in the ordinary JVM `test` task rather than in
 * `androidTest`, deliberately. The emulator step is this project's least
 * reliable CI surface — it has already cost a 27-minute hang and three
 * commits of cache-key archaeology — and an integration suite is the last
 * thing that should inherit that. `androidTest` keeps the one thing that
 * genuinely needs a device: `MigrationTest`, which validates the exported
 * schemas against real SQLite upgrades.
 *
 * The pieces that are *not* real, and why:
 *
 *  - [TokenStore] is a mock. It is `EncryptedSharedPreferences` over the
 *    Android keystore, which Robolectric does not implement; the real one
 *    silently reads back empty. Everything downstream only ever asks it for
 *    [StoredCredentials], so a mock is a faithful stand-in.
 *  - [SessionManager] is relaxed-mocked. Tests that care about session
 *    transitions assert on it directly.
 *  - TLS is a self-signed certificate for `localhost`, because
 *    `HostInterceptor` enforces https (S-21) and would refuse MockWebServer's
 *    cleartext default. Keeping the enforcement in the chain is the point.
 */
internal class IntegrationEnvironment private constructor(
    val context: Context,
    val server: MockWebServer,
    val db: NcCollectivesDatabase,
    val tokenStore: TokenStore,
    val sessionManager: SessionManager,
    val client: OkHttpClient,
    val api: CollectivesApiService,
    val bodyService: PageBodyService,
    val accountGeneration: AccountGeneration,
    val syncScheduler: SyncScheduler,
) : AutoCloseable {
    val attachmentRepository: AttachmentRepositoryImpl =
        AttachmentRepositoryImpl(
            context = context,
            api = api,
            pageDao = db.pageDao(),
            attachmentDao = db.attachmentDao(),
            bodyService = bodyService,
            syncScheduler = syncScheduler,
            database = db,
        )

    val pageRepository: PageRepositoryImpl =
        PageRepositoryImpl(
            api = api,
            bodyService = bodyService,
            pageDao = db.pageDao(),
            editQueueDao = db.editQueueDao(),
            attachmentDao = db.attachmentDao(),
            syncScheduler = syncScheduler,
            database = db,
            accountGeneration = accountGeneration,
            attachmentRepository = { attachmentRepository as AttachmentRepository },
        )

    /** The `https://host:port` the mocked credential names. */
    val host: String get() = server.url("/").toString().trimEnd('/')

    /**
     * Insert a page row directly, bypassing the network. [bodyMd] is the
     * *server's* copy of the body — the invariant issue #18 rests on — and
     * [draftBodyMd] is a parked conflict draft.
     */
    suspend fun seedPage(
        id: Long,
        collectiveId: Long = COLLECTIVE_ID,
        parentId: Long = 0,
        title: String = "Page $id",
        fileName: String = "$title.md",
        filePath: String = "",
        collectivePath: String = COLLECTIVE_PATH,
        bodyMd: String? = null,
        bodyEtag: String? = null,
        draftBodyMd: String? = null,
    ): PageEntity {
        val entity = PageEntity(
            id = id,
            collectiveId = collectiveId,
            parentId = parentId,
            title = title,
            emoji = null,
            tagsCsv = "",
            subpageOrderCsv = "",
            isFullWidth = false,
            trashTimestamp = null,
            serverTimestamp = 0,
            size = 0,
            fileName = fileName,
            filePath = filePath,
            collectivePath = collectivePath,
            linkedPageIdsCsv = "",
            lastUserDisplayName = "",
            bodyMd = bodyMd,
            bodyEtag = bodyEtag,
            draftBodyMd = draftBodyMd,
            lastSyncedAt = 0,
        )
        db.pageDao().upsertAll(listOf(entity))
        return entity
    }

    /**
     * A staged upload as `enqueueUpload` would have left it: a row plus the
     * bytes it points at, in the cache directory the repository reads from.
     */
    suspend fun seedStagedUpload(
        pageId: Long,
        fileName: String,
        bytes: ByteArray = "staged".toByteArray(),
        status: String = AttachmentEntity.STATUS_PENDING,
        attempts: Int = 0,
    ): AttachmentEntity {
        val key = AttachmentEntity.key(pageId, fileName)
        val staged = File(File(context.cacheDir, "attachments-pending"), key.replace('/', '_'))
        staged.parentFile?.mkdirs()
        staged.writeBytes(bytes)
        val entity = AttachmentEntity(
            id = key,
            pageId = pageId,
            fileName = fileName,
            contentType = "image/jpeg",
            size = bytes.size.toLong(),
            lastModifiedMs = 0,
            etag = null,
            status = status,
            localUriString = android.net.Uri
                .fromFile(staged)
                .toString(),
            lastSyncedAt = 0,
            attempts = attempts,
        )
        db.attachmentDao().upsert(entity)
        return entity
    }

    /** The staging file behind an attachment row, whether or not it exists. */
    fun stagedFile(
        pageId: Long,
        fileName: String,
    ): File =
        File(
            File(context.cacheDir, "attachments-pending"),
            AttachmentEntity.key(pageId, fileName).replace('/', '_'),
        )

    override fun close() {
        db.close()
        server.shutdown()
    }

    companion object {
        const val COLLECTIVE_ID = 7L
        const val COLLECTIVE_PATH = ".Collectives/Wiki"

        fun create(): IntegrationEnvironment {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // Synchronous executor so `SyncScheduler`'s enqueues settle
            // before the assertion that follows them, rather than on a
            // background thread the test cannot see.
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration
                    .Builder()
                    .setExecutor(SynchronousExecutor())
                    .setTaskExecutor(SynchronousExecutor())
                    .build(),
            )

            val certificate = HeldCertificate
                .Builder()
                .addSubjectAlternativeName("localhost")
                .build()
            val serverCertificates = HandshakeCertificates
                .Builder()
                .heldCertificate(certificate)
                .build()
            val clientCertificates = HandshakeCertificates
                .Builder()
                .addTrustedCertificate(certificate.certificate)
                .build()
            val server = MockWebServer()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()

            val tokenStore = mockk<TokenStore>()
            every { tokenStore.getCredentials() } returns
                StoredCredentials(
                    host = server.url("/").toString().trimEnd('/'),
                    loginName = LOGIN_NAME,
                    appPassword = "app-password",
                )
            val sessionManager = mockk<SessionManager>(relaxed = true)

            val client = NetworkModule
                .provideOkHttpClient(
                    hostInterceptor = HostInterceptor(tokenStore),
                    authInterceptor = AuthInterceptor(tokenStore, sessionManager),
                ).newBuilder()
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager,
                ).build()

            val json = NetworkModule.provideJson()
            val api = NetworkModule.provideCollectivesApi(NetworkModule.provideRetrofit(client, json))
            val db = Room
                .inMemoryDatabaseBuilder(context, NcCollectivesDatabase::class.java)
                .build()

            return IntegrationEnvironment(
                context = context,
                server = server,
                db = db,
                tokenStore = tokenStore,
                sessionManager = sessionManager,
                client = client,
                api = api,
                bodyService = PageBodyService(client, tokenStore),
                accountGeneration = AccountGeneration(),
                syncScheduler = SyncScheduler(context, UserPreferences(context)),
            )
        }

        const val LOGIN_NAME = "alice"
    }
}
