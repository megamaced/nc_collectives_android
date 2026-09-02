package com.megamaced.nccollectives.data.auth

/**
 * What to do when the server stops accepting an account's credential.
 *
 * Declared next to [SessionManager] and implemented by [AccountSwitcher], so
 * the class that *notices* an expiry doesn't have to depend on the class that
 * *acts* on one. Issue #19: `SessionManager` used to handle it itself, with
 * `beginSignOut()` + `endSignOut()` and nothing in between — a path that
 * reaches neither [LocalDataWiper] nor `TokenStore.removeAccount`. So an
 * expiry left the whole of the previous account's cache on disk for the next
 * sign-in to inherit (the cold-sign-in path deliberately doesn't wipe,
 * because it assumes there is nothing to wipe), and deleted *every* stored
 * account to punish the one credential that failed.
 */
interface ExpiredSessionHandler {
    /**
     * [accountId] was the active account when the 401 streak crossed the
     * threshold. Implementations must tolerate it no longer being active, or
     * no longer being stored at all: the streak is detected on an OkHttp
     * thread and a switch or removal can land first.
     */
    fun onSessionExpired(accountId: String)
}

/**
 * The consecutive-401 counter behind [ExpiredSessionHandler]. Pulled out of
 * [SessionManager] because it is the whole of the policy and none of the
 * Android — `SessionManager` reaches `EncryptedSharedPreferences` through
 * `TokenStore`, so nothing about it can be unit-tested, and three separate
 * findings have now been argued about in its comments.
 *
 * Not thread-confined by construction: [onResponse] is called from
 * `AuthInterceptor`, i.e. from whichever OkHttp thread happens to carry the
 * response, and several can land at once.
 */
internal class AuthFailureTracker(
    private val threshold: Int = CONSECUTIVE_401_THRESHOLD,
) {
    private val consecutive401s = java.util.concurrent.atomic
        .AtomicInteger(0)

    /**
     * Armed until an expiry is dispatched, then re-armed by the next
     * response that isn't a 401.
     *
     * Issue #19: without it, every 401 after the threshold dispatches
     * again. That was survivable when expiry was a self-contained state
     * flip; now that it removes an account and wipes the device it must
     * happen once per streak. The streak is counted across several
     * concurrent requests, so "once" has to be a compare-and-set rather
     * than an equality test on the count.
     */
    private val armed = java.util.concurrent.atomic
        .AtomicBoolean(true)

    /**
     * @return true when this response means the credential is dead and the
     * caller should expire the session.
     */
    fun onResponse(code: Int): Boolean {
        if (code != 401) {
            // B-51: reset on *any* non-401, not just 2xx. `code in 200..299`
            // meant a transient `401 -> 500 -> 401` sequence (a flaky reverse
            // proxy, say) still signed the user out — the 5xx was no evidence
            // of a working auth exchange, but no evidence of a dead token
            // either. Only consecutive 401s stack.
            consecutive401s.set(0)
            armed.set(true)
            return false
        }
        val n = consecutive401s.incrementAndGet()
        return n >= threshold && armed.compareAndSet(true, false)
    }

    /** Forget the streak — a new credential is live. */
    fun reset() {
        consecutive401s.set(0)
        armed.set(true)
    }

    companion object {
        /**
         * Two in a row before we treat the session as invalid. B-2: picks up
         * genuine token rejection on the next failure, while ignoring a
         * single transient proxy 401 or a 401 from some non-Collectives
         * resource the user happened to request.
         */
        const val CONSECUTIVE_401_THRESHOLD = 2
    }
}
