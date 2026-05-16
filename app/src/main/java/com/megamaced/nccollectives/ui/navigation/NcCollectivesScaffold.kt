package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.megamaced.nccollectives.data.auth.AuthState
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.ui.screen.login.LoginScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
internal class AuthGateViewModel
    @Inject
    constructor(
        sessionManager: SessionManager,
        sharePayloadHolder: SharePayloadHolder,
    ) : ViewModel() {
        val authState = sessionManager.authState
        val sharePayload: StateFlow<SharePayload?> = sharePayloadHolder.payload
    }

@Composable
internal fun NcCollectivesScaffold(viewModel: AuthGateViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsState()
    val sharePayload by viewModel.sharePayload.collectAsState()

    when (authState) {
        AuthState.Unknown -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        AuthState.Unauthenticated -> LoginScreen()
        AuthState.Authenticated -> AuthenticatedHost(hasSharePayload = sharePayload != null)
    }
}

@Composable
private fun AuthenticatedHost(hasSharePayload: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(hasSharePayload) {
        if (hasSharePayload && currentRoute != Destination.ShareCapture.route) {
            navController.navigate(Destination.ShareCapture.route) {
                launchSingleTop = true
            }
        }
    }

    Scaffold { innerPadding ->
        NcCollectivesNavHost(navController = navController, innerPadding = innerPadding)
    }
}
