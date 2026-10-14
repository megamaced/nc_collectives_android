package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Every call is bound to an account when it is made — the one in force where
 * it is made, or else the current one — and refused if that account has gone
 * by the time it is sent.
 */
class AccountBoundCallFactoryTest {
    private val server = MockWebServer()
    private val generation = AccountGeneration()
    private val factory = AccountBoundCallFactory(
        OkHttpClient.Builder().addInterceptor(AccountBindingInterceptor(generation)).build(),
        generation,
    )

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = Request.Builder().url(server.url("/")).build()

    private fun switchAccount() {
        generation.invalidate()
        generation.settle()
    }

    @Test
    fun aCallMadeOnTheCurrentAccountGoesOut() {
        server.enqueue(MockResponse())

        factory.newCall(request()).execute().use { assertEquals(200, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun aCallMadeBeforeASwitchAndSentAfterItGoesNowhere() {
        val call = factory.newCall(request())
        switchAccount()

        assertThrows(AccountChangedException::class.java) { call.execute() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCallMadeWhileASwitchIsUnderWayGoesNowhere_evenOnceItHasLanded() {
        generation.invalidate()
        val call = factory.newCall(request())
        generation.settle()

        assertThrows(AccountChangedException::class.java) { call.execute() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aBindingAlreadyOnTheRequestIsKept() {
        val captured = generation.current()
        switchAccount()
        val tagged = request().newBuilder().tag(AccountBinding::class.java, AccountBinding(captured)).build()

        val call = factory.newCall(tagged)

        assertThrows(AccountChangedException::class.java) { call.execute() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aBindingInForceWhereTheCallIsMadeOutranksTheCurrentAccount() {
        val captured = generation.current()
        switchAccount()

        val call = runBlocking { withContext(AccountBinding(captured)) { factory.newCall(request()) } }

        assertThrows(AccountChangedException::class.java) { call.execute() }
        assertEquals(0, server.requestCount)
    }
}
