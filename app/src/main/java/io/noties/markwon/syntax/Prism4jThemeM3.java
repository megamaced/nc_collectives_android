package io.noties.markwon.syntax;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

/**
 * Material 3 palette mapped onto Prism4j's token names (Batch 24).
 * <p>
 * Lives in {@code io.noties.markwon.syntax} (not under the
 * {@code com.megamaced} tree) because {@code Prism4jThemeBase.ColorHashMap}
 * exposes only {@code protected} {@code add(...)} methods. Java's
 * protected access requires either a subclass of {@code ColorHashMap}
 * (which we don't want — we'd inherit map semantics) or a same-package
 * caller (this file). Same shape as the upstream {@code Prism4jThemeDefault}
 * and {@code Prism4jThemeDarkula} classes — we just take colours via
 * the constructor so the M3 colour scheme threads through unchanged
 * from {@code MarkdownView}.
 * <p>
 * Mapping (per batch spec):
 * <ul>
 *   <li>background = surfaceContainerHigh</li>
 *   <li>fallback   = onSurface</li>
 *   <li>keywords   = primary</li>
 *   <li>strings    = tertiary</li>
 *   <li>comments   = outline</li>
 *   <li>numbers/literals = secondary</li>
 * </ul>
 * Plus function-name + operator/punctuation tints so chained call
 * sites don't render as flat onSurface.
 */
public final class Prism4jThemeM3 extends Prism4jThemeBase {

    private final int backgroundColor;
    private final int textColor;
    private final int keywordColor;
    private final int stringColor;
    private final int commentColor;
    private final int literalColor;
    private final int functionColor;
    private final int operatorColor;

    public Prism4jThemeM3(
        @ColorInt int backgroundColor,
        @ColorInt int textColor,
        @ColorInt int keywordColor,
        @ColorInt int stringColor,
        @ColorInt int commentColor,
        @ColorInt int literalColor,
        @ColorInt int functionColor,
        @ColorInt int operatorColor
    ) {
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.keywordColor = keywordColor;
        this.stringColor = stringColor;
        this.commentColor = commentColor;
        this.literalColor = literalColor;
        this.functionColor = functionColor;
        this.operatorColor = operatorColor;
    }

    @Override
    public int background() {
        return backgroundColor;
    }

    @Override
    public int textColor() {
        return textColor;
    }

    @NonNull
    @Override
    protected ColorHashMap init() {
        return new ColorHashMap()
            // Comments and metadata-like tokens
            .add(commentColor, "comment", "prolog", "doctype", "cdata", "important")
            // Strings and char literals
            .add(stringColor, "string", "char", "url", "attr-value", "regex")
            // Numbers, booleans, hex literals
            .add(literalColor, "number", "boolean", "constant", "symbol")
            // Keywords and control flow
            .add(keywordColor, "keyword", "atrule", "selector", "tag", "builtin")
            // Function definitions / calls
            .add(functionColor, "function", "class-name", "namespace")
            // Operators / punctuation — slightly muted so chained calls don't shout
            .add(operatorColor, "operator", "punctuation", "entity", "attr-name")
            // Inline diff markers
            .add(literalColor, "inserted")
            .add(commentColor, "deleted");
    }
}
