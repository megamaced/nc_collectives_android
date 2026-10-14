package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Binds the network requests made inside it to one account: the one that
 * [generation] (an [AccountGeneration] value) was captured under.
 *
 * Issue #20's guard keeps a stale *response* out of the cache. This keeps a
 * stale *request* off the next account's server. `HostInterceptor` and
 * `AuthInterceptor` read the host and password as each request is sent, not
 * when it was made, so a request made for the outgoing account and sent
 * after a switch would go to the incoming account's server under its
 * credentials — a page body written into whatever that server has under the
 * same path.
 *
 * Every call made through [AccountBoundCallFactory] carries a binding as a
 * tag, and [AccountBindingInterceptor] refuses it once the account has
 * changed. By default that is the account current when the call is made.
 * Work that reads the cache and then sends what it read has to be bound
 * from before the read instead: use this as a coroutine context element
 * around it, `withContext(AccountBinding(generation)) { … }`, and every
 * call made inside carries that binding.
 */
class AccountBinding(
    val generation: Long,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<AccountBinding?> {
    companion object Key : CoroutineContext.Key<AccountBinding>

    // A thread-local as well as a context element, because Retrofit builds
    // its OkHttp call from plain (non-suspending) code on the caller's
    // thread, where the coroutine context can't be asked.
    override fun updateThreadContext(context: CoroutineContext): AccountBinding? {
        val previous = current.get()
        current.set(this)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: AccountBinding?,
    ) {
        current.set(oldState)
    }
}

private val current = ThreadLocal<AccountBinding?>()

/**
 * The one way the app makes a call on the account's server: Retrofit, the
 * WebDAV body service, and the two image loaders all go through it. Each
 * call is bound to an account when it is made: by the [AccountBinding]
 * already on the request, else the one in force where it is made, else the
 * account current at that moment.
 *
 * The default matters as much as an explicit binding: a call made for one
 * account can sit in OkHttp's queue, or between `HostInterceptor` and
 * `AuthInterceptor`, while a switch lands, and then go out with the next
 * account's host or password.
 */
@Singleton
class AccountBoundCallFactory
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val accountGeneration: AccountGeneration,
    ) : Call.Factory {
        override fun newCall(request: Request): Call {
            if (request.tag(AccountBinding::class.java) != null) return client.newCall(request)
            val binding = current.get() ?: AccountBinding(accountGeneration.current())
            return client.newCall(request.newBuilder().tag(AccountBinding::class.java, binding).build())
        }
    }

/**
 * Runs [block] on one account, handing it that account's generation for the
 * cache writes it makes. The account is the one already bound around this
 * call, if there is one, so an operation nested in another, or run by a
 * worker that bound its whole run, stays on the outer one's account; and
 * otherwise the one current now.
 *
 * For work that reads the cache and then sends or writes what it read. Bound
 * from before the read, what was read for one account goes nowhere once a
 * switch has begun: its requests are refused, and its writes under the
 * generation are dropped.
 */
suspend fun <T> AccountGeneration.bound(block: suspend (generation: Long) -> T): T {
    currentCoroutineContext()[AccountBinding]?.let { return block(it.generation) }
    val generation = current()
    return withContext(AccountBinding(generation)) { block(generation) }
}

/**
 * Work refused because the account it was started on has been switched
 * away: a request before it went out, or a write before it landed. The work
 * is abandoned, not failed: nothing retries it, queues it or reports the
 * network.
 */
class AccountChangedException(
    message: String = "The account changed before this request was sent",
) : IOException(message)

/**
 * Refuses a request bound to an account that is no longer the current one.
 *
 * Registered after `HostInterceptor` and `AuthInterceptor`, and one check
 * there is enough. Credentials only change inside a switch's window, and no
 * generation is current inside one or after it — see [AccountGeneration].
 * So if the binding's generation is current here, no window has opened
 * since it was captured, nor was one open when it was: the credentials did
 * not change in that time, and the host and password the two interceptors
 * just read are the bound account's. Otherwise the request goes nowhere.
 */
class AccountBindingInterceptor
    @Inject
    constructor(
        private val accountGeneration: AccountGeneration,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val binding = chain.request().tag(AccountBinding::class.java)
            if (binding != null && !accountGeneration.isCurrent(binding.generation)) throw AccountChangedException()
            return chain.proceed(chain.request())
        }
    }
