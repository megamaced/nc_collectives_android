package com.megamaced.nccollectives

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        publishShareIfPresent(intent)
        setContent {
            NcCollectivesTheme {
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
