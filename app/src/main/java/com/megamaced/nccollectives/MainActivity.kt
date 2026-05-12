package com.megamaced.nccollectives

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.megamaced.nccollectives.ui.navigation.NcCollectivesScaffold
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NcCollectivesTheme {
                NcCollectivesScaffold()
            }
        }
    }
}
