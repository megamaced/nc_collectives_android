package com.megamaced.nccollectives

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.ui.navigation.NcCollectivesScaffold
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import com.megamaced.nccollectives.util.UpdateChecker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sharePayloadHolder: SharePayloadHolder

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var updateChecker: UpdateChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        publishShareIfPresent(intent)
        // Fire-and-forget — no-ops if we checked within the last 24h or
        // the user is already on the latest version. Notification is
        // posted out-of-band via NotificationManager; nothing here waits
        // on the result.
        lifecycleScope.launch { updateChecker.checkOnStartup() }
        setContent {
            val prefs by userPreferences.flow.collectAsState(initial = UserPrefs())
            NcCollectivesTheme(themeMode = prefs.themeMode) {
                NcCollectivesScaffold()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishShareIfPresent(intent)
    }

    private fun publishShareIfPresent(intent: Intent?) {
        if (intent == null) return
        val payload = SharePayload.fromIntent(intent) ?: return
        sharePayloadHolder.publish(payload)
    }
}
