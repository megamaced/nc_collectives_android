package com.megamaced.nccollectives.ui.components

import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.SimpleTagHandler

/**
 * Draws `<mark>` — what [com.megamaced.nccollectives.util.rewriteHighlights]
 * makes of Nextcloud Text's `==text==` — as highlighted text: [background]
 * behind it, and [foreground] for the text itself so it stays readable on
 * that background in either theme. Markwon's `HtmlPlugin` has no handler of
 * its own for `mark`, and without one the text renders plain.
 */
internal class MarkTagHandler(
    @param:ColorInt private val background: Int,
    @param:ColorInt private val foreground: Int,
) : SimpleTagHandler() {
    override fun getSpans(
        configuration: MarkwonConfiguration,
        renderProps: RenderProps,
        tag: HtmlTag,
    ): Any = arrayOf<Any>(BackgroundColorSpan(background), ForegroundColorSpan(foreground))

    override fun supportedTags(): Collection<String> = listOf("mark")
}
