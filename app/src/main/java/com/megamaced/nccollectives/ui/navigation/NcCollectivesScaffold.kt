package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
         * Where to land on launch: Home, the collective list, or a collective.
         *
         * The stored startup collective is validated against the Room cache
         * rather than trusted blind — the collective may have been trashed or
         * deleted server-side since the user picked it, and navigating to a
         * dead id would drop them into an empty page tree with no explanation.
         * The decision itself lives in [resolveStartupRoute]; this does the
         * reads and acts on the verdict, so the result is never
         * [StartupRoute.StaleDefault].
         *
         * `cachedCollectives()` is the one-shot snapshot read (R-30) — no
         * point starting a Flow subscription for a single launch decision.
         */
        suspend fun startupRoute(): StartupRoute {
            val prefs = userPreferences.flow.first()
            val wanted = prefs.defaultCollectiveId
            val cachedIds = if (wanted == null) emptyList() else collectiveRepository.cachedCollectives().map { it.id }
            return when (val route = resolveStartupRoute(prefs.startScreen, wanted, cachedIds)) {
                is StartupRoute.StaleDefault -> {
                    Timber.i("Default collective %d no longer exists; clearing the setting", wanted)
                    userPreferences.setDefaultCollectiveId(null)
                    route.fallback
                }

                else -> {
                    route
                }
            }
        }
    }

@Composable
internal fun NcCollectivesScaffold(viewModel: AuthGateViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val sharePayload by viewModel.sharePayload.collectAsStateWithLifecycle()

    when (authState) {
        AuthState.Unknown -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        AuthState.Unauthenticated -> {
            LoginScreen()
        }

        // Mounted in place of the authenticated host, which is what tears
        // every Room flow observer down before `AccountSwitcher` wipes the
        // tables they are reading (issue #14).
        AuthState.Switching -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Switching account…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        AuthState.Authenticated -> {
            AuthenticatedHost(
                sharePayload = sharePayload,
                startupRoute = viewModel::startupRoute,
            )
        }
    }
}

@Composable
private fun AuthenticatedHost(
    sharePayload: SharePayload?,
    startupRoute: suspend () -> StartupRoute,
    alertBadge: AlertBadgeViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val unseenAlerts by alertBadge.unseenCount.collectAsStateWithLifecycle()
    LifecycleStartEffect(alertBadge) {
        alertBadge.refreshIfStale()
        onStopOrDispose { }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentRoute = destination?.route
    val currentTab = destination?.topLevelTab()

    // Keyed on the payload *identity*, not on `!= null` (B-80): a second
    // share arriving while the first is still unconsumed leaves presence
    // unchanged, so a boolean key never re-fires and the new payload is
    // silently dropped. When the capture screen is already on top no
    // navigation is needed — `ShareCaptureViewModel` observes the holder
    // and swaps its own state.
    LaunchedEffect(sharePayload) {
        if (sharePayload != null && currentRoute != Destination.ShareCapture.ROUTE) {
            navController.navigate(Destination.ShareCapture.ROUTE) {
                launchSingleTop = true
            }
        }
    }

    // Settings → Startup. Fires at most once per launch.
    //
    // `rememberSaveable`, not `remember`: a configuration change recreates
    // the activity, and with a plain `remember` the user would be yanked back
    // to their startup destination every time they rotated the phone. Saved
    // state is dropped on a genuine cold start, which is exactly when we *do*
    // want to route again. (Same reasoning as B-37 on
    // `PageViewScreen.pendingTrash`.)
    var startupRouted by rememberSaveable { mutableStateOf(false) }
    // Presence, not identity: this must fire at most once per launch, so a
    // second share must not re-run it (it is `startupRouted`-guarded too).
    LaunchedEffect(sharePayload != null) {
        if (startupRouted) return@LaunchedEffect
        // A share intent is the whole reason the app was opened — it wins,
        // and we mark startup handled so the startup destination doesn't land
        // on top of the capture screen once the payload is consumed.
        startupRouted = true
        if (sharePayload != null) return@LaunchedEffect
        val route = startupRoute()
        // Reading DataStore + Room takes a moment; if the user has already
        // tapped something in that window, don't yank them somewhere they
        // didn't ask for. `currentDestination` is read live rather than
        // through the captured `currentRoute` snapshot.
        if (navController.currentDestination?.route != TopLevelTab.Home.rootRoute) {
            return@LaunchedEffect
        }
        when (route) {
            StartupRoute.Home, is StartupRoute.StaleDefault -> {
                Unit
            }

            StartupRoute.CollectiveList -> {
                navController.navigateToTab(TopLevelTab.Collectives)
            }

            // Pushed on top of the collective list rather than replacing it,
            // so Back still reaches the list — otherwise setting a startup
            // collective would leave no way to switch collectives.
            is StartupRoute.OpenCollective -> {
                navController.navigateToTab(TopLevelTab.Collectives)
                navController.navigate(Destination.PageTree.route(TopLevelTab.Collectives, route.collectiveId))
            }
        }
    }

    val navigationState = rememberNavigationSuiteScaffoldState()
    val navigationScope = rememberCoroutineScope()
    val navigationBar = remember(navigationState, navigationScope) {
        NavigationBarController(navigationState, navigationScope)
    }
    // A screen may hide the bar while its content scrolls (the page view
    // does); every new destination starts with it showing.
    LaunchedEffect(backStackEntry?.id) { navigationBar.show() }

    val reselections = remember { TabReselections() }

    val adaptiveType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
    LaunchedEffect(adaptiveType) { navigationBar.hidesOnScroll = adaptiveType.isHorizontalBar() }
    val navigationType =
        if (destination == null || !destination.showsNavigationBar()) NavigationSuiteType.None else adaptiveType

    NavigationSuiteScaffold(
        navigationItems = {
            TopLevelTabItems(
                currentTab = currentTab,
                unseenAlerts = unseenAlerts,
                navigationSuiteType = adaptiveType,
                onTabClick = { tab, selected -> navController.onTabClicked(tab, selected, reselections) },
            )
        },
        navigationSuiteType = navigationType,
        state = navigationState,
    ) {
        CompositionLocalProvider(LocalNavigationBarController provides navigationBar) {
            Scaffold { innerPadding ->
                NcCollectivesNavHost(navController = navController, innerPadding = innerPadding, reselections = reselections)
            }
        }
    }
}

/** The navigation bar's items, one per tab, with the unseen-alerts badge on Alerts. */
@Composable
internal fun TopLevelTabItems(
    currentTab: TopLevelTab?,
    unseenAlerts: Int,
    navigationSuiteType: NavigationSuiteType,
    onTabClick: (tab: TopLevelTab, selected: Boolean) -> Unit,
) {
    TopLevelTab.entries.forEach { tab ->
        val selected = tab == currentTab
        NavigationSuiteItem(
            selected = selected,
            onClick = { onTabClick(tab, selected) },
            icon = {
                Icon(
                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = null,
                )
            },
            label = { TabLabel(tab.label) },
            badge = if (tab == TopLevelTab.Alerts && unseenAlerts > 0) {
                {
                    Badge(
                        modifier = Modifier.semantics {
                            contentDescription = "$unseenAlerts new"
                        },
                    ) { Text(badgeText(unseenAlerts)) }
                }
            } else {
                null
            },
            navigationSuiteType = navigationSuiteType,
        )
    }
}

/**
 * A tab's label, on one line. A label too wide for its slot — "Collectives"
 * on a narrow phone, or with the font enlarged — shrinks to fit rather than
 * breaking mid-word, to no less than [SMALLEST_TAB_LABEL] of its size at
 * the default font size; a label still too wide is cut short with an
 * ellipsis.
 */
@Composable
internal fun TabLabel(text: String) {
    val size = LocalTextStyle.current.fontSize
    val fontScale = LocalDensity.current.fontScale
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = if (size.isSp) {
            ShrinkToFit(smallest = size * (SMALLEST_TAB_LABEL / fontScale).coerceAtMost(1f), largest = size)
        } else {
            null
        },
    )
}

/** The smallest a tab label shrinks to, as a share of its size at the default font size. */
internal const val SMALLEST_TAB_LABEL = 0.8f

/**
 * The largest size, in [step]s down from [largest] to no less than
 * [smallest], at which the text fits without an ellipsis — so text that
 * fits is drawn at exactly [largest]. `TextAutoSize.StepBased` counts its
 * steps up from the smallest size, and its top step can fall short of the
 * largest.
 */
private data class ShrinkToFit(
    val smallest: TextUnit,
    val largest: TextUnit,
    val step: TextUnit = 0.25.sp,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        fun size(steps: Int): TextUnit = (largest.value - steps * step.value).sp

        fun fits(steps: Int): Boolean = performLayout(constraints, text, size(steps)).let { !it.isLineEllipsized(it.lineCount - 1) }

        if (fits(0)) return largest
        val most = ((largest.value - smallest.value) / step.value).toInt().coerceAtLeast(0)
        if (!fits(most)) return size(most)
        var tooFew = 0
        var enough = most
        while (enough - tooFew > 1) {
            val steps = (tooFew + enough) / 2
            if (fits(steps)) enough = steps else tooFew = steps
        }
        return size(enough)
    }
}
