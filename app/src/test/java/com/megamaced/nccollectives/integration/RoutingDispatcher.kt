package com.megamaced.nccollectives.integration

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Routes MockWebServer replies by method and path instead of by arrival
 * order.
 *
 * `MockWebServer.enqueue` is a single FIFO queue, which cannot describe the
 * code under test here: `PageRepositoryImpl.refresh` fires the tag list and
 * the page list concurrently (R-48), so which request arrives first is a race
 * and an order-keyed fixture is a coin flip. Several other paths issue a
 * WebDAV call and an OCS call from the same operation.
 *
 * A route with more than one response hands them out in order and then keeps
 * repeating the last, so "the first save 412s and every retry after that
 * succeeds" is expressible without counting requests.
 */
internal class RoutingDispatcher : Dispatcher() {
    private data class Route(
        val method: String?,
        val pathFragment: String,
        val responses: List<MockResponse>,
    ) {
        var served: Int = 0
    }

    private val routes = mutableListOf<Route>()
    private val received = ConcurrentLinkedQueue<RecordedRequest>()
    private val hooks = mutableListOf<Pair<String, (RecordedRequest) -> Unit>>()

    /** Every request the server saw, in arrival order. */
    val requests: List<RecordedRequest> get() = received.toList()

    fun requestsTo(pathFragment: String): List<RecordedRequest> = requests.filter { it.path?.contains(pathFragment) == true }

    fun requestsWithMethod(method: String): List<RecordedRequest> = requests.filter { it.method == method }

    /**
     * Answer requests whose path contains [pathFragment] (and whose method is
     * [method], when given) with [responses] in order, repeating the last one
     * once they run out.
     *
     * Later routes win over earlier ones, so a test can lay down a broad
     * default and then override one path.
     */
    fun on(
        pathFragment: String,
        vararg responses: MockResponse,
        method: String? = null,
    ): RoutingDispatcher {
        require(responses.isNotEmpty()) { "A route needs at least one response" }
        routes += Route(method, pathFragment, responses.toList())
        return this
    }

    /**
     * Run [action] when a request for [pathFragment] arrives, before its
     * response is sent.
     *
     * This is how a test lands an event *while a request is in flight*
     * without sleeping: the hook runs on the server's thread with the caller
     * still suspended inside `Call.execute`, which is exactly the window
     * issue #20's account-switch race lives in.
     */
    fun whileInFlight(
        pathFragment: String,
        action: (RecordedRequest) -> Unit,
    ): RoutingDispatcher {
        hooks += pathFragment to action
        return this
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        received += request
        val path = request.path.orEmpty()
        hooks.forEach { (fragment, action) -> if (path.contains(fragment)) action(request) }
        val route = routes.lastOrNull { candidate ->
            path.contains(candidate.pathFragment) &&
                (candidate.method == null || candidate.method == request.method)
        }
        if (route == null) {
            // Loud rather than a default 200: an unrouted request is a test
            // that does not describe what the code actually does, and a
            // silent empty body turns that into a confusing parse failure
            // several frames away.
            return MockResponse()
                .setResponseCode(HTTP_NOT_IMPLEMENTED)
                .setBody("No route for ${request.method} $path")
        }
        val response = route.responses[minOf(route.served, route.responses.lastIndex)]
        route.served++
        return response
    }

    private companion object {
        const val HTTP_NOT_IMPLEMENTED = 501
    }
}
