package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.ThreadContextElement
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
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
 * Use as a coroutine context element around work that must stay on its
 * account: `withContext(AccountBinding(generation)) { … }`. Every request
 * made inside it, through Retrofit or [com.megamaced.nccollectives.data.api.PageBodyService],
 * carries the binding as a tag, and [AccountBindingInterceptor] refuses it
 * once the account has changed. Requests made outside a binding are sent as
 * they always were.
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

/** [this], tagged with the [AccountBinding] in force on this thread, if there is one. */
internal fun Request.boundToAccount(): Request {
    val binding = current.get() ?: return this
    return newBuilder().tag(AccountBinding::class.java, binding).build()
}

/** A bound request refused because its account was switched away before it went out. */
class AccountChangedException : IOException("The account changed before this request was sent")

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
