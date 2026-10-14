package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.ui.navigation.LocalNavigationBarController
import com.megamaced.nccollectives.ui.navigation.NavigationBarController
import com.megamaced.nccollectives.ui.navigation.isHorizontalBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [ReadingChrome] with real gestures, inside the same arrangement the
 * page view uses: a navigation suite, a Scaffold carrying [readingChrome] and
 * an enter-always top bar, and pull-to-refresh around a vertically scrolling
 * body.
 *
 * The v2 rule runs effects on a queued dispatcher, as a device does. The v1
 * rule's immediate dispatch hid the race where a late hide overtook a reveal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ReadingChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var navigationState: NavigationSuiteScaffoldState
    private lateinit var chrome: ReadingChrome
    private lateinit var scrollState: ScrollState
    private val lines = mutableIntStateOf(0)
    private var refreshes = 0
    private lateinit var scope: CoroutineScope
    private var viewportWindowY = 0f
    private var content: LayoutCoordinates? = null
    private var heading: LayoutCoordinates? = null

    /**
     * [wrapping] gives the body paragraphs whose line breaks depend on its
     * width, with a heading marker half way down; otherwise it is fixed-height
     * lines.
     */
    private fun show(
        bodyLines: Int,
        suiteType: NavigationSuiteType = NavigationSuiteType.ShortNavigationBarCompact,
        wrapping: Boolean = false,
    ) {
        lines.intValue = bodyLines
        compose.setContent {
            navigationState = rememberNavigationSuiteScaffoldState()
            scope = rememberCoroutineScope()
            val controller = remember { NavigationBarController(navigationState, scope) }
            // As the app scaffold does.
            LaunchedEffect(suiteType) { controller.hidesOnScroll = suiteType.isHorizontalBar() }
            NavigationSuiteScaffold(
                navigationItems = { TabItem(suiteType) },
                navigationSuiteType = suiteType,
                state = navigationState,
            ) {
                CompositionLocalProvider(LocalNavigationBarController provides controller) {
                    ReadingPage(wrapping)
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun TabItem(suiteType: NavigationSuiteType) {
        NavigationSuiteItem(
            selected = true,
            onClick = {},
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Home") },
            navigationSuiteType = suiteType,
        )
    }

    @Composable
    private fun ReadingPage(wrapping: Boolean = false) {
        scrollState = rememberScrollState()
        chrome = rememberReadingChrome(scrollState)
        Scaffold(
            modifier = Modifier.readingChrome(chrome),
            topBar = {
                TopAppBar(title = { Text("Page") }, scrollBehavior = chrome.topBarScrollBehavior)
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { refreshes++ },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                val count by lines
                Column(
                    Modifier
                        .fillMaxSize()
                        // Outside the scroll: the viewport, which stays put.
                        .onGloballyPositioned { viewportWindowY = it.positionInWindow().y }
                        .verticalScroll(scrollState)
                        .testTag(BODY)
                        // Inside it: the content, which moves with the scroll.
                        .onGloballyPositioned { content = it },
                ) {
                    repeat(count) { index ->
                        val line = if (wrapping) Modifier else Modifier.height(48.dp)
                        val marked = if (index == count / 2) {
                            line.onGloballyPositioned { heading = it }
                        } else {
                            line
                        }
                        Text(if (wrapping) PARAGRAPH else "Line $index", marked)
                    }
                }
            }
        }
    }

    private val topBarOffset get() = chrome.topBarScrollBehavior.state.heightOffset
    private val topBarLimit get() = chrome.topBarScrollBehavior.state.heightOffsetLimit

    private fun assertChromeShowing() {
        compose.runOnIdle {
            assertEquals(NavigationSuiteScaffoldValue.Visible, navigationState.currentValue)
            assertEquals(0f, topBarOffset)
            assertFalse(chrome.scrollingForward)
        }
    }

    private fun assertChromeHidden() {
        compose.runOnIdle {
            assertEquals(NavigationSuiteScaffoldValue.Hidden, navigationState.currentValue)
            assertEquals(topBarLimit, topBarOffset)
            assertTrue(chrome.scrollingForward)
        }
    }

    private fun swipeBodyUp() {
        compose.onNodeWithTag(BODY).performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    @Test
    fun readingForwardHidesTheChrome_andScrollingBackShowsIt() {
        show(bodyLines = 100)
        assertChromeShowing()

        swipeBodyUp()
        assertChromeHidden()

        compose.onNodeWithTag(BODY).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test
    fun aBodyThatFitsTheScreenHidesNothing() {
        show(bodyLines = 2)
        swipeBodyUp()
        assertChromeShowing()
    }

    @Test
    fun aPullAtTheTopRevealsTheTopBarBeforeItRefreshes() {
        show(bodyLines = 100)
        swipeBodyUp()
        // Back to the top without a gesture, so the bar stays collapsed.
        compose.runOnIdle { runBlocking { scrollState.scrollTo(0) } }
        compose.runOnIdle { assertEquals(topBarLimit, topBarOffset) }

        // Pull-to-refresh counts half of each pixel dragged and fires at
        // 80dp, so it needs 160dp of finger travel. Of a 190dp pull, the
        // collapsed 64dp bar takes its whole height first, leaving too little
        // to refresh. Were refresh served first, this pull would fire it.
        pullDown(190.dp)
        compose.runOnIdle {
            assertEquals(0f, topBarOffset)
            assertEquals(0, refreshes)
        }

        // With the bar showing, the whole of the next pull goes to refresh.
        pullDown(190.dp)
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    /** A slow drag down from the top of the body, released without a fling. */
    private fun pullDown(distance: Dp) {
        compose.onNodeWithTag(BODY).performTouchInput {
            down(Offset(centerX, top + 4.dp.toPx()))
            val steps = 20
            val step = distance.toPx() / steps
            repeat(steps) { moveBy(Offset(0f, step), delayMillis = 16) }
            // Hold still before lifting, so the release carries no velocity.
            moveBy(Offset.Zero, delayMillis = 200)
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun revealShowsEverythingAgain() {
        show(bodyLines = 100)
        swipeBodyUp()
        assertChromeHidden()

        compose.runOnIdle { chrome.reveal() }
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test
    fun chromeHiddenOverABodyThatStopsScrollingComesBack() {
        show(bodyLines = 100)
        swipeBodyUp()
        assertChromeHidden()

        // A refresh that shortened the page: no gesture could bring the
        // chrome back now, so it has to come back by itself.
        compose.runOnIdle { lines.intValue = 2 }
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test
    fun aRevealWinsOverAHideRequestedJustBeforeIt() {
        show(bodyLines = 100)
        compose.runOnIdle {
            chrome.directionConnection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput)
            chrome.reveal()
        }
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test
    fun readingForwardAfterARevealHidesTheChromeAgain() {
        show(bodyLines = 100)
        swipeBodyUp()
        assertChromeHidden()
        compose.runOnIdle { chrome.reveal() }
        compose.waitForIdle()
        assertChromeShowing()

        swipeBodyUp()
        assertChromeHidden()
    }

    @Test
    fun aRailStaysPut_whileTheTopBarStillGivesWay() {
        show(bodyLines = 100, suiteType = NavigationSuiteType.WideNavigationRailCollapsed)
        swipeBodyUp()
        compose.runOnIdle {
            assertEquals(NavigationSuiteScaffoldValue.Visible, navigationState.currentValue)
            assertEquals(topBarLimit, topBarOffset)
            assertTrue(chrome.scrollingForward)
        }
    }

    /**
     * The page view's index jump, made from part-way down the page with the
     * chrome hidden: reveal, then work the target out at once from the
     * heading's content-space offset.
     */
    private fun jumpToHeading(headroomPx: Float) {
        compose.runOnIdle {
            chrome.reveal()
            val offset = content!!.localPositionOf(heading!!, Offset.Zero).y
            scope.launch { scrollState.animateScrollTo((offset - headroomPx).toInt().coerceAtLeast(0)) }
        }
        compose.waitForIdle()
    }

    private val headingBelowViewportTop: Float
        get() = heading!!.positionInWindow().y - viewportWindowY

    @Test
    fun aHeadingJumpFromPartWayDownLandsJustUnderTheTopBar() {
        show(bodyLines = 100)
        swipeBodyUp()
        compose.runOnIdle { assertTrue("starts part-way down", scrollState.value > 0) }
        val headroomPx = with(compose.density) { 12.dp.toPx() }

        jumpToHeading(headroomPx)

        assertChromeShowing()
        compose.runOnIdle { assertEquals(headroomPx, headingBelowViewportTop, 2f) }
    }

    @Test
    fun aHeadingJumpBesideARailLandsJustUnderTheTopBar() {
        // The rail never hides, so revealing the chrome can't re-wrap the
        // text out from under a target already worked out.
        show(bodyLines = 30, suiteType = NavigationSuiteType.WideNavigationRailCollapsed, wrapping = true)
        swipeBodyUp()
        val headroomPx = with(compose.density) { 12.dp.toPx() }

        jumpToHeading(headroomPx)

        assertChromeShowing()
        compose.runOnIdle { assertEquals(headroomPx, headingBelowViewportTop, 2f) }
    }

    @Test
    fun aPageStillFlingingAfterItWasLeftCannotHideTheNextScreensBar() {
        lateinit var nav: NavHostController
        compose.setContent {
            navigationState = rememberNavigationSuiteScaffoldState()
            scope = rememberCoroutineScope()
            val controller = remember { NavigationBarController(navigationState, scope) }
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            // As the app scaffold does.
            LaunchedEffect(entry?.id) { controller.show() }
            NavigationSuiteScaffold(
                navigationItems = { TabItem(NavigationSuiteType.ShortNavigationBarCompact) },
                navigationSuiteType = NavigationSuiteType.ShortNavigationBarCompact,
                state = navigationState,
            ) {
                CompositionLocalProvider(LocalNavigationBarController provides controller) {
                    NavHost(navController = nav, startDestination = "reading") {
                        composable("reading") { ReadingPage() }
                        composable("next") { Text("Next destination", Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        lines.intValue = 200
        compose.waitForIdle()

        // Leave while the fling is still running, then let it all settle.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(BODY).performTouchInput { swipeUp(durationMillis = 150) }
        compose.mainClock.advanceTimeBy(32L)
        compose.runOnIdle { nav.navigate("next") }
        compose.mainClock.advanceTimeBy(32L)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("next", nav.currentDestination?.route)
            assertEquals(NavigationSuiteScaffoldValue.Visible, navigationState.currentValue)
        }
    }

    private companion object {
        const val BODY = "body"
        val PARAGRAPH = "A paragraph whose line breaks depend on how wide the page is. ".repeat(12)
    }
}
