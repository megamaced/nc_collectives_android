package com.megamaced.nccollectives

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.ui.navigation.NcCollectivesScaffold
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sharePayloadHolder: SharePayloadHolder

    @Inject
    lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // B-74: only a fresh start may publish. This activity is
        // `singleTask`, so a share intent stays its current intent
        // indefinitely — an unconditional publish here re-published a payload
        // the user had already saved on every recreation (rotation, theme
        // change, the system reclaiming the process), the navigation effect
        // dragged them back into the capture screen with content that was
        // already on the server, and tapping Create made a duplicate page.
        // A non-null `savedInstanceState` is exactly "this is a recreation,
        // not a new share".
        if (savedInstanceState == null) publishShareIfPresent(intent)
        setContent {
            val prefs by userPreferences.flow.collectAsStateWithLifecycle(initialValue = UserPrefs())
            NcCollectivesTheme(themeMode = prefs.themeMode, textScale = prefs.textScale) {
                NcCollectivesScaffold()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishShareIfPresent(intent)
    }

    /**
     * Publish a share payload at most once per intent.
     *
     * The `savedInstanceState` gate in [onCreate] handles recreation; the
     * marker extra handles everything else that can hand us the same intent
     * object twice — `onNewIntent` for an intent we already took, and a
     * `getIntent()` re-read after the activity was rebuilt in this process.
     * `onNewIntent` still publishes normally: a genuinely new share arrives
     * as a new intent, without the marker.
     */
    private fun publishShareIfPresent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_SHARE_HANDLED, false)) return
        val payload = SharePayload.fromIntent(intent) ?: return
        intent.putExtra(EXTRA_SHARE_HANDLED, true)
        sharePayloadHolder.publish(payload)
    }

    private companion object {
        /** Marks a share intent this activity has already handed to the holder. */
        const val EXTRA_SHARE_HANDLED = "com.megamaced.nccollectives.SHARE_HANDLED"
    }
}
