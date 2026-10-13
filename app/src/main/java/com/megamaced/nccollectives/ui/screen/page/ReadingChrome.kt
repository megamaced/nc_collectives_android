package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.megamaced.nccollectives.ui.components.ScrollDirection
import com.megamaced.nccollectives.ui.components.ScrollDirectionConnection
import com.megamaced.nccollectives.ui.navigation.LocalNavigationBarController
import com.megamaced.nccollectives.ui.navigation.NavigationBarController

/**
 * The chrome around content being read: the top app bar, the app's
 * navigation bar, and whether the screen's FAB keeps its label.
 *
 * Scrolling forward slides the top app bar and the navigation bar away and
 * marks [scrollingForward], so the FAB can shrink to its icon. Scrolling
 * back, or pulling at the top, brings them back. On content that fits the
 * screen nothing scrolls, so nothing hides; and if content stops being
 * scrollable while the chrome is hidden, [rememberReadingChrome] reveals it
 * again, since no gesture could.
 *
 * The navigation bar belongs to the whole app, so it is only touched while
 * [isActive]: a page the user has just left can still be flinging, and its
 * scroll must not hide the bar under the screen that replaced it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class ReadingChrome(
    val topBarScrollBehavior: TopAppBarScrollBehavior,
    private val navigationBar: NavigationBarController?,
    private val isActive: () -> Boolean,
    canScroll: () -> Boolean,
    thresholdPx: Float,
) {
    var scrollingForward by mutableStateOf(false)
        private set

    /** Observes direction only; it consumes nothing. */
    val directionConnection =
        ScrollDirectionConnection(thresholdPx, canScroll) { direction ->
            scrollingForward = direction == ScrollDirection.Forward
            if (isActive()) {
                if (scrollingForward) navigationBar?.hide() else navigationBar?.show()
            }
        }

    /**
     * Show everything again. For scrolls made in code, which don't pass
     * through nested scrolling and so would leave the chrome as it was.
     */
    fun reveal() {
        topBarScrollBehavior.state.heightOffset = 0f
        if (isActive()) navigationBar?.show()
        scrollingForward = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberReadingChrome(scrollState: ScrollState): ReadingChrome {
    val canScroll by remember(scrollState) {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    // One lambda instance, so the scroll behaviour below isn't rebuilt on
    // every recomposition.
    val canScrollProvider = remember(scrollState) { { canScroll } }
    val topBar = TopAppBarDefaults.enterAlwaysScrollBehavior(canScroll = canScrollProvider)
    val navigationBar = LocalNavigationBarController.current
    val thresholdPx = with(LocalDensity.current) { CHROME_SCROLL_THRESHOLD.toPx() }
    // Inside the nav host this is the destination's back-stack entry, which
    // drops below RESUMED as soon as another destination is navigated to.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val chrome = remember(topBar, navigationBar, thresholdPx, lifecycle) {
        ReadingChrome(
            topBarScrollBehavior = topBar,
            navigationBar = navigationBar,
            isActive = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            canScroll = canScrollProvider,
            thresholdPx = thresholdPx,
        )
    }
    LaunchedEffect(chrome, canScroll) {
        if (!canScroll) chrome.reveal()
    }
    return chrome
}

/**
 * Attach [chrome] to the screen's Scaffold, above the scrollable and above
 * any pull-to-refresh. Pre-scroll reaches the outermost parent first:
 *
 * 1. The direction observer, outermost, sees the whole delta and consumes
 *    none of it.
 * 2. The top bar takes what it needs to collapse or reveal itself.
 * 3. Pull-to-refresh, inside the Scaffold, gets the rest — so a pull at the
 *    top reveals the bar before any of it starts a refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.readingChrome(chrome: ReadingChrome): Modifier =
    nestedScroll(chrome.directionConnection)
        .nestedScroll(chrome.topBarScrollBehavior.nestedScrollConnection)

/** How far a scroll travels one way before the reading chrome follows it. */
private val CHROME_SCROLL_THRESHOLD = 24.dp
