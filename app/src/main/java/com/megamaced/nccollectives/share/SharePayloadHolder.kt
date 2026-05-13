package com.megamaced.nccollectives.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        fun consume() {
            _payload.value = null
        }
    }
