package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.megamaced.nccollectives.data.auth.AuthState
import com.megamaced.nccollectives.data.auth.SessionManager
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
        val sharePayload: StateFlow<com.megamaced.nccollectives.share.SharePayload?> =
            sharePayloadHolder.payload
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
        AuthState.Authenticated -> AuthenticatedScaffold(hasSharePayload = sharePayload != null)
    }
}

@Composable
private fun AuthenticatedScaffold(hasSharePayload: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    androidx.compose.runtime.LaunchedEffect(hasSharePayload) {
        if (hasSharePayload && currentRoute != Destination.ShareCapture.route) {
            navController.navigate(Destination.ShareCapture.route) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.bottomBar.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NcCollectivesNavHost(navController = navController, innerPadding = innerPadding)
    }
}
