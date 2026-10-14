package com.megamaced.nccollectives.data.auth

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which account's data the local cache is allowed to hold.
 *
 * Bumped by [LocalDataWiper] before it clears anything, so a write issued
 * under the outgoing account can tell that it is no longer wanted. Issue #20:
 * cancelling work is not a barrier — `WorkManager.cancelUniqueWork` is
 * asynchronous, and `EditFlushWorker.recordPutOutcome` deliberately runs
 * `NonCancellable` (B-64, so a body the server accepted is never left with no
 * local record of it), which means cancellation *cannot* stop it even in
 * principle. A worker holding an account A response could therefore commit it
 * after the tables were cleared, and `AccountSwitcher` would then activate
 * account B on top of one account's pages, attachments and queued writes.
 *
 * The guard has to sit *inside* the same Room transaction as the write it
 * protects, which is what makes it airtight rather than merely narrow. Room
 * serialises transactions, and the wipe bumps the generation before opening
 * the one that clears the tables, so for any guarded write either:
 *
 *  - its transaction commits first, and the wipe's clear then removes what it
 *    wrote; or
 *  - its transaction starts after the bump, sees a stale generation, and
 *    abandons.
 *
 * There is no third ordering. Note which writes need it: an `UPDATE` or a
 * `DELETE` landing after the clear matches no rows and is harmless, so the
 * guard belongs on the upserts — those are what resurrect a wiped account's
 * data.
 *
 * A switch is a window, not an instant: the outgoing account's credentials
 * and cache are still in place while the wipe runs, and the incoming
 * account's arrive only at its end. Nothing is current inside that window —
 * not even work that started during it, which could otherwise read the
 * outgoing account and then commit, or send, once the incoming one is live.
 * The value is odd while a switch or sign-out is under way and even once one
 * account (or none) is settled in: [invalidate] opens the window and
 * [settle] closes it with a value nothing has captured. Every credential
 * change happens inside a window, so a capture that is still current has
 * seen the same account throughout — which `AccountBinding` relies on.
 *
 * Process-wide rather than persisted on purpose. It answers "has the account
 * changed since this in-memory coroutine started", which has no meaning
 * across a process restart — and a restart has no in-flight writes to
 * arbitrate.
 */
@Singleton
class AccountGeneration
    @Inject
    constructor() {
        private val generation = AtomicLong(0)

        /**
         * Capture before issuing the network request whose response will be
         * written, not after it returns — the point is to notice a wipe that
         * happened while the request was in flight.
         */
        fun current(): Long = generation.get()

        /**
         * Whether a write captured at [captured] may still be committed: no
         * switch has begun since, and none was under way when it was taken.
         */
        fun isCurrent(captured: Long): Boolean = captured % 2 == 0L && generation.get() == captured

        /**
         * A switch or sign-out begins: nothing issued under the current
         * account, or from now until [settle], may commit. Called by
         * [LocalDataWiper] before it touches anything.
         */
        fun invalidate() {
            generation.updateAndGet { if (it % 2 == 0L) it + 1 else it + 2 }
        }

        /**
         * The switch or sign-out has landed: the next account's credentials
         * are active, or there are none. Work captured from here on is
         * current. Called by `SessionManager` as it ends a session change,
         * before the state it publishes lets any new work start.
         */
        fun settle() {
            generation.updateAndGet { if (it % 2 == 0L) it + 2 else it + 1 }
        }
    }
