package com.megamaced.nccollectives.util

import io.noties.prism4j.annotations.PrismBundle

/**
 * Tells the Prism4j bundler annotation processor (kapt) which language
 * grammars to compile into the app. Each include lands as a generated
 * `Prism_<lang>` class plus an entry in a generated `GrammarLocator`
 * implementation that `MarkdownView` instantiates.
 *
 * Adding a language: append its Prism4j grammar slug here, then reference
 * the generated GrammarLocator from `MarkdownView.kt`. The set below
 * covers what we actually publish to Collectives pages — every code
 * fence whose `info` string matches one of these gets highlighted; the
 * rest fall through to Markwon's plain monospace block.
 */
@PrismBundle(
    // prism4j-bundler 2.0.0 doesn't ship grammars for `bash` (no shell
    // language in the bundle at all) or `typescript`. Closest mappings:
    // - bash → no fallback; shell snippets render as plain monospace
    //   (Markwon falls through to its default code-block style).
    // - typescript → `javascript` works adequately on common TS pages
    //   (annotations are highlighted as type comments).
    // Also pulled in `java` and `python` since they're cheap and common
    // in our notes — total bundle stays small (each Prism_* class is a
    // few KB).
    include = ["kotlin", "json", "yaml", "markdown", "javascript", "java", "python"],
    grammarLocatorClassName = ".CollectivesGrammarLocator",
)
internal object PrismGrammars
