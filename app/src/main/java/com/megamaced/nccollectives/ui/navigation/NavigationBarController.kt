package com.megamaced.nccollectives.ui.navigation

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lets a screen slide the app's navigation bar out of the way and back, for
 * reading long content.
 *
 * The latest request wins. The value asked for is recorded as the call is
 * made and any animation still pending is cancelled, so a show that follows
 * a hide in the same frame cannot be overtaken by the hide starting late.
 *
 * The scaffold shows the bar again whenever the destination changes, so a
 * screen that hides it never has to remember to restore it on the way out.
 * Hiding releases the bar's space to the content, and the scaffold stops
 * consuming its window inset once the animation settles.
 */
internal class NavigationBarController(
    private val state: NavigationSuiteScaffoldState,
    private val scope: CoroutineScope,
) {
    private var requested: NavigationSuiteScaffoldValue = state.targetValue
    private var pending: Job? = null

    /**
     * Whether [hide] does anything: true for a horizontal navigation bar,
     * false for a rail. A rail sits beside the content, so hiding it would
     * widen the text and re-wrap it under the reader's eyes, and would move
     * every position a scroll target was worked out from.
     */
    var hidesOnScroll: Boolean = true
        set(value) {
            field = value
            if (!value) show()
        }

    fun show() = request(NavigationSuiteScaffoldValue.Visible)

    fun hide() {
        if (hidesOnScroll) request(NavigationSuiteScaffoldValue.Hidden)
    }

    private fun request(value: NavigationSuiteScaffoldValue) {
        if (value == requested && (pending?.isActive == true || state.targetValue == value)) return
        requested = value
        pending?.cancel()
        pending = scope.launch {
            when (value) {
                NavigationSuiteScaffoldValue.Visible -> state.show()
                NavigationSuiteScaffoldValue.Hidden -> state.hide()
            }
        }
    }
}

/** The navigation suite types that draw a horizontal bar, which may hide while reading. */
internal fun NavigationSuiteType.isHorizontalBar(): Boolean =
    this == NavigationSuiteType.ShortNavigationBarCompact ||
        this == NavigationSuiteType.ShortNavigationBarMedium ||
        this == NavigationSuiteType.NavigationBar

/** Null outside the authenticated scaffold (the sign-in screen, previews and tests). */
internal val LocalNavigationBarController = staticCompositionLocalOf<NavigationBarController?> { null }
