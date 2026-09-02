package com.megamaced.nccollectives.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-process handoff for share intents. `MainActivity` parses the
 * intent into a [SharePayload] and writes it here; `NcCollectivesNavHost`
 * observes the flow and navigates to `ShareCaptureScreen` once the user
 * is authenticated. The screen calls [consume] when it has taken
 * ownership so a back-press doesn't reopen the share UI.
 */
@Singleton
class SharePayloadHolder
    @Inject
    constructor() {
        private val _payload = MutableStateFlow<SharePayload?>(null)
        val payload: StateFlow<SharePayload?> = _payload.asStateFlow()

        fun publish(payload: SharePayload) {
            _payload.value = payload
        }

        /**
         * Clear [payloadId], and only [payloadId].
         *
         * Issue #25: this used to null the field unconditionally, so if
         * Android delivered share B while share A was still saving, A's
         * completion discarded B and finished the activity. B-80 had already
         * made the *arrival* of a second share observable by keying on
         * payload identity; the clear was never given the same treatment, so
         * the arrival was noticed and then thrown away.
         *
         * `update` rather than a read-then-write: a save completing on a
         * `viewModelScope` coroutine and an intent arriving on the main
         * thread are not ordered with respect to each other.
         */
        fun consume(payloadId: String) {
            _payload.update { current -> if (current?.id == payloadId) null else current }
        }

        /**
         * Drop whatever is held, whoever put it there. For the session
         * transitions only — S-16: a share captured under one account must
         * not be replayed into the next one's Nextcloud, and there is no
         * payload id to match against at that point.
         */
        fun discard() {
            _payload.value = null
        }
    }
