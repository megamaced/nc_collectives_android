package com.megamaced.nccollectives.util

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * Text for the user, named rather than spelled out, so whatever produces it
 * — a view model, a repository, a worker — needs no Context and no language.
 * The screen turns it into words against the resources of the moment:
 * [resolve] in composition, `resolve(resources)` outside it. Resolving late
 * is what lets text already on screen follow a change of language.
 *
 * An argument that is itself a [UiText] is resolved first, in the same
 * language.
 */
sealed interface UiText {
    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /**
     * A quantity string. [quantity] picks the grammatical form — one, few,
     * many, other: whichever the language has — and [args] fill the format
     * arguments, which are what the text shows. They are separate because a
     * string can show more than its count, but a string usually shows the
     * count it is chosen by, as `%1$d`, so [args] defaults to [quantity].
     */
    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = listOf(quantity),
    ) : UiText

    /**
     * Text with nothing to translate: what a server or an exception said.
     * Never the app's own words, which are a [Res] or a [Plural].
     */
    data class Raw(
        val value: String,
    ) : UiText
}

/** A [UiText.Res] for [id], with [args] as its format arguments. */
fun uiText(
    @StringRes id: Int,
    vararg args: Any,
): UiText = UiText.Res(id, args.toList())

fun UiText.resolve(resources: Resources): String =
    when (this) {
        is UiText.Res -> {
            if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.resolvedFor(resources))
        }

        is UiText.Plural -> {
            resources.getQuantityString(id, quantity, *args.resolvedFor(resources))
        }

        is UiText.Raw -> {
            value
        }
    }

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = resolve(LocalResources.current)

private fun List<Any>.resolvedFor(resources: Resources): Array<Any> = map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
