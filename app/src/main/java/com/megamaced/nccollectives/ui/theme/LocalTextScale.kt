package com.megamaced.nccollectives.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Multiplier applied to page body text — the rendered markdown, the
 * native editor's text field, and (as a `textZoom` percentage) the
 * collaborative editor's WebView. Provided by [NcCollectivesTheme] from
 * the user's `TextScale` preference; `1f` when unset.
 *
 * A CompositionLocal rather than a ViewModel field because the three
 * consumers span two ViewModels and one shared component
 * ([com.megamaced.nccollectives.ui.components.MarkdownView]), and
 * because it's read-mostly global state with the same lifetime as the
 * theme it ships alongside. `static` for the same reason M3 uses it for
 * colours: reads are frequent, writes are a settings change, and
 * invalidating the whole subtree on that is the cheaper trade.
 */
val LocalTextScale = staticCompositionLocalOf { 1f }
