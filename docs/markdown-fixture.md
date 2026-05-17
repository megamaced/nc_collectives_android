# Markdown rendering fixture

Eyeball-diff page exercising every Markwon plugin the app ships with. Drop
this into a Collective and open it on-device after touching `MarkdownView.kt`
or bumping any Markwon dep — anything that used to render and now doesn't
is a regression.

Plugins covered (in source order):

- `LinkifyPlugin` — autolinking
- `StrikethroughPlugin` — `~~text~~`
- `HtmlPlugin` — inline HTML (Batch 24)
- `ImagesPlugin` — image refs through the authenticated OkHttp client
- `TablePlugin` — GFM tables
- `TaskListPlugin` — `- [ ]` / `- [x]`
- `SyntaxHighlightPlugin` + Prism4j — fenced code blocks with a known language tag (Batch 24)

---

## 1. Paragraph basics

A paragraph with **bold**, *italic*, ~~strikethrough~~, and an `inline code` span.
A naked URL — https://nextcloud.com — should autolink via `LinkifyPlugin`.

A second paragraph for vertical-rhythm checks. The default Markwon line height
should leave readable space between block elements without feeling sparse.

## 2. Inline HTML (Batch 24)

H<sub>2</sub>O and E = mc<sup>2</sup>.

A hard line break in HTML:<br>this sits on a new line without a blank one.

A `<details>` element that is **not** an interactive widget in Markwon —
contents render inline. This is the documented behaviour.

<details>
<summary>Click to expand</summary>

The summary still shows as text and the body renders right after it. Not
collapsible. Intentional — Markwon doesn't ship a `<details>` widget and
custom span work was out of scope for Batch 24.

</details>

## 3. Wikilinks and backlinks

In-app navigation should resolve [[Task List]] (this page lives next to it)
and a Markdown ref like [Task List](./Task%20List.md) too. A nonexistent
[[Some Made Up Page]] should surface a "Page not found" snackbar.

## 4. Tables

| Plugin                  | Status | Notes                                    |
| ----------------------- | :----: | ---------------------------------------- |
| HtmlPlugin              |   ✅   | Inline tags + alignment                  |
| SyntaxHighlightPlugin   |   ✅   | Prism4j: kotlin/json/yaml/markdown/js/java/python |
| FootnotesPlugin         |   ❌   | Not shipped in Markwon 4.6.2             |

## 5. Task list

- [x] Tables still render after Batch 24 plugin shuffle
- [x] Task-list checkboxes still tint with `MaterialTheme.colorScheme.primary`
- [ ] Open this fixture in dark mode and verify keyword colour is legible
- [ ] Open in light mode and check the same

## 6. Syntax-highlighted code blocks (Batch 24)

### Kotlin

```kotlin
package com.megamaced.nccollectives

import io.noties.markwon.Markwon

class Greeter(private val name: String) {
    // Comment colour comes from MaterialTheme.colorScheme.outline.
    fun hello(): String {
        val greeting = "Hello, $name!"
        return greeting.uppercase()
    }
}
```

### JSON

```json
{
  "id": 42,
  "title": "An example page",
  "tags": [1, 2, 3],
  "active": true
}
```

### YAML

```yaml
name: NcCollectives
version: 1.1.0
languages:
  - kotlin
  - json
  - yaml
  - markdown
features:
  syntaxHighlight: true
```

### Markdown (inception)

```markdown
# A heading

A paragraph with **bold** and *italic*.

- item one
- item two
```

### JavaScript (also used as the typescript fallback)

```javascript
const greet = (name) => {
  // 'typescript' tag falls through to javascript highlighting.
  return `Hello, ${name}!`;
};
```

### Java

```java
public class Greeter {
    private final String name;

    public Greeter(String name) {
        this.name = name;
    }

    public String hello() {
        return "Hello, " + name + "!";
    }
}
```

### Python

```python
def greet(name: str) -> str:
    """Comment colour should match the kotlin block above."""
    return f"Hello, {name}!"
```

### Unknown language (falls back to monospace)

```bash
# `bash` isn't bundled in prism4j-bundler 2.0.0 — this block should render
# as plain monospace on the same surfaceContainerHigh background.
echo "hello"
```

## 7. Inline images

The next image points at a relative ref. In a real page this resolves against
the page's WebDAV attachments directory via `OkHttpNetworkSchemeHandler`.

![alt text](attachment-placeholder.png)

---

End of fixture. If anything above looks wrong, capture a screenshot and
attach it to the audit findings page before bisecting.
