package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #60: the poll's token goes to the poll endpoint and nowhere a redirect points. */
class LoginPollRedirectTest {
    private val poll = MockWebServer()
    private val elsewhere = MockWebServer()

    @After
    fun tearDown() {
        poll.shutdown()
        elsewhere.shutdown()
    }

    @Test
    fun aRedirectedPollIsRefused_andTheTokenGoesNowhereElse() =
        runTest {
            elsewhere.enqueue(MockResponse().setBody("""{"server":"https://cloud.example","loginName":"a","appPassword":"p"}"""))
            for (code in listOf(301, 302, 307, 308)) {
                poll.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/steal").toString()))
            }

            repeat(4) {
                val status = NextcloudLoginFlow().poll(endpoint = poll.url("/login/v2/poll").toString(), token = "secret")
                assertTrue("was $status", status is LoginFlowStatus.Error)
            }

            assertEquals(4, poll.requestCount)
            assertEquals("the token must not reach the redirect's target", 0, elsewhere.requestCount)
        }
}
