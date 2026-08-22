package com.megamaced.nccollectives

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.megamaced.nccollectives.sync.SyncScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class NcCollectivesApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    @Inject
    lateinit var okHttpClient: Lazy<OkHttpClient>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        syncScheduler.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    syncScheduler.syncNow()
                    syncScheduler.flushEditsWhenOnline()
                    syncScheduler.flushAttachmentUploadsWhenOnline()
                }
            },
        )
    }

    /**
     * Coil is deliberately wired to the *shared, authenticated*
     * `OkHttpClient`: every image the app loads over the network is an
     * attachment on the user's own Nextcloud, served from WebDAV, which
     * 401s without Basic-auth. A separate unauthenticated loader would
     * therefore break inline images and attachment thumbnails outright.
     *
     * What keeps that safe is the pair of guards on the client itself, not
     * the loader: `HostInterceptor` refuses any request whose URL this app
     * didn't build (S-23), and `MarkdownView.absolutizeImageRefs` demotes
     * off-host image refs in a page body to plain links before Coil or
     * Markwon ever sees them (S-24). Removing either one turns this shared
     * client back into an authenticated fetcher aimed by page content.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okHttpClient.get() },
                    ),
                )
            }.crossfade(true)
            .build()
}
