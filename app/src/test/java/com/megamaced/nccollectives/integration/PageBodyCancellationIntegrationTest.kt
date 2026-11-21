package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PageBodyCancellationIntegrationTest {
    @Test
    fun cancellingARevalidationCancelsItsHttpCall() =
        runTest {
            val started = CompletableDeferred<Call>()
            val release = CompletableDeferred<Unit>()
            val fixture = IntegrationEnvironment.create {
                addInterceptor { chain ->
                    // Hold the actual asynchronous call before socket IO can time it out.
                    started.complete(chain.call())
                    runBlocking { release.await() }
                    throw IOException("Revalidation fixture finished")
                }
            }
            fixture.use { env ->
                env.seedPage(id = 41, fileName = "p41.md", bodyMd = "Server A", bodyEtag = "e1", bodyTimestamp = 100)
                try {
                    val refresh = async { env.pageRepository.refreshBodyIfChanged(41) }
                    val call = started.await()
                    refresh.cancelAndJoin()

                    assertTrue(call.isCanceled())
                    assertEquals("Server A", env.pageRepository.getPage(41)?.bodyMd)
                } finally {
                    release.complete(Unit)
                }
            }
        }
}
