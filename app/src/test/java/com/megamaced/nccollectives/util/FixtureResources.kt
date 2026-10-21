package com.megamaced.nccollectives.util

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider

/**
 * The app's resources with some strings swapped for [strings]: wording the
 * app doesn't ship, such as a translation a test needs, keyed by resource
 * id. Every other string comes from the app. Needs Robolectric.
 */
@Suppress("DEPRECATION") // The only public constructor; a test needs no more.
class FixtureResources(
    private val strings: Map<Int, String>,
    base: Resources = ApplicationProvider.getApplicationContext<Context>().resources,
) : Resources(base.assets, base.displayMetrics, base.configuration) {
    override fun getString(id: Int): String = strings[id] ?: super.getString(id)

    override fun getString(
        id: Int,
        vararg formatArgs: Any?,
    ): String = strings[id]?.format(*formatArgs) ?: super.getString(id, *formatArgs)
}
