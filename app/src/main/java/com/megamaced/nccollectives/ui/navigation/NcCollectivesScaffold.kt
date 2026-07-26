package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.megamaced.nccollectives.data.auth.AuthState
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.ui.screen.login.LoginScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
internal class AuthGateViewModel
    @Inject
    constructor(
        sessionManager: SessionManager,
        sharePayloadHolder: SharePayloadHolder,
        private val userPreferences: UserPreferences,
        private val collectiveRepository: CollectiveRepository,
    ) : ViewModel() {
        val authState = sessionManager.authState
        val sharePayload: StateFlow<SharePayload?> = sharePayloadHolder.payload

        /**
         * Collective to open straight into on launch, or null to stay on the
         * collective list.
         *
         * The stored default is validated against the Room cache rather than
         * trusted blind — the collective may have been trashed or deleted
         * server-side since the user picked it, and navigating to a dead id
         * would drop them into an empty page tree with no explanation. The
         * decision itself lives in [resolveStartupRoute]; this just does the
         * two reads and acts on the verdict.
         *
         * `cachedCollectives()` is the one-shot snapshot read (R-30) — no
         * point starting a Flow subscription for a single launch decision.
         */
        suspend fun resolveStartupCollective(): Long? {
            val wanted = userPreferences.flow.first().defaultCollectiveId
            val cachedIds = if (wanted == null) emptyList() else collectiveRepository.cachedCollectives().map { it.id }
            return when (val route = resolveStartupRoute(wanted, cachedIds)) {
                is StartupRoute.OpenCollective -> route.collectiveId
                StartupRoute.CollectiveList -> null
                StartupRoute.StaleDefault -> {
                    Timber.i("Default collective %d no longer exists; clearing the setting", wanted)
                    userPreferences.setDefaultCollectiveId(null)
                    null
                }
            }
        }
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
        AuthState.Authenticated -> AuthenticatedHost(
            hasSharePayload = sharePayload != null,
            resolveStartupCollective = viewModel::resolveStartupCollective,
        )
    }
}

@Composable
private fun AuthenticatedHost(
    hasSharePayload: Boolean,
    resolveStartupCollective: suspend () -> Long?,
) {
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

    // "Open this collective on launch" (Settings → Startup). Fires at most
    // once per launch.
    //
    // `rememberSaveable`, not `remember`: a configuration change recreates
    // the activity, and with a plain `remember` the user would be yanked back
    // into their default collective every time they rotated the phone while
    // on the collective list. Saved state is dropped on a genuine cold start,
    // which is exactly when we *do* want to route again. (Same reasoning as
    // B-37 on `PageViewScreen.pendingTrash`.)
    var startupRouted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasSharePayload) {
        if (startupRouted) return@LaunchedEffect
        // A share intent is the whole reason the app was opened — it wins,
        // and we mark startup handled so the default doesn't land on top of
        // the capture screen once the payload is consumed.
        startupRouted = true
        if (hasSharePayload) return@LaunchedEffect
        val collectiveId = resolveStartupCollective() ?: return@LaunchedEffect
        // Reading DataStore + Room takes a moment; if the user has already
        // tapped a collective (or anything else) in that window, don't yank
        // them somewhere they didn't ask for. `currentDestination` is read
        // live rather than through the captured `currentRoute` snapshot.
        if (navController.currentDestination?.route != Destination.Collectives.route) {
            return@LaunchedEffect
        }
        // Pushed on top of the list rather than replacing it, so Back still
        // reaches the picker — otherwise setting a default would leave no way
        // to switch collectives.
        navController.navigate(Destination.PageTree.route(collectiveId))
    }

    Scaffold { innerPadding ->
        NcCollectivesNavHost(navController = navController, innerPadding = innerPadding)
    }
}
