package com.megamaced.nccollectives.util

import java.net.URLDecoder

// Classification helpers for scheme-less markdown targets that point at a
// page *attachment* rather than at another wiki page.
//
// Collectives keeps per-page attachments in a sibling `.attachments.<pageId>`
// directory next to the page's own markdown file, and refers to them from the
// body with a *relative* target. Two shapes appear in the wild:
//
//  - bare filename — `![photo](photo.jpg)` / `[report.pdf](report.pdf)`. What
//    this app's own editor emits (`MarkdownToolbarActions.insertAttachment`)
//    and what `MarkdownView.absolutizeImageRefs` was built around.
//  - directory-qualified — `[report.pdf](.attachments.1234/report.pdf)`.
//
// Both are scheme-less, so before this file existed both fell through
// `handleMarkdownLink`'s `null`-scheme branch into the wiki-page *title*
// resolver, and tapping a PDF surfaced `Linked page "…" not found`. The
// upstream Collectives maintainer reported exactly that (GitHub issue #1,
// comment 5031076685: "attachments (PDFs, office files, and so on) don't work
// well yet with your Markdown viewer").
//
// Everything here is a pure string function so it can be unit-tested on the
// JVM without the Android framework — the same constraint that shapes
// `decodeWikiTarget` and `TextDialectExtensions`.

/** A markdown target resolved to a file inside a page's attachment folder. */
data class AttachmentRef(
    /**
     * Path **relative to the page file's own directory**, always including
     * the `.attachments.<pageId>` segment — e.g. `.attachments.12/report.pdf`.
     * Bare-filename targets are expanded against the *current* page's
     * attachment directory; already-qualified targets are kept verbatim so a
     * body copied between pages still points at the folder it named.
     */
    val relativePath: String,
    /** Last path segment — the file name on its own. */
    val fileName: String,
)

/**
 * Classify [target] (a scheme-less markdown link/image target) for the page
 * identified by [pageId]. Returns null when the target should keep its
 * existing treatment as a wiki-page reference.
 *
 * Ordered, deliberately conservative:
 *
 *  1. any `.attachments.<digits>` path segment → attachment. Authoritative:
 *     no page title can produce that segment.
 *  2. otherwise a single segment whose extension is in [FILE_EXTENSIONS] →
 *     attachment in *this* page's folder. An allowlist rather than
 *     "has a dot" because page titles routinely contain dots — a page
 *     called `Release 2.4` must not be mistaken for a file named `4`.
 *  3. otherwise → null (wiki page, unchanged behaviour).
 */
fun parseAttachmentRef(
    target: String,
    pageId: Long,
): AttachmentRef? {
    val cleaned = cleanRelativePath(target)
    if (cleaned.isEmpty()) return null
    val segments = cleaned.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null
    // S-14′ posture: a target that tries to walk out of the page directory
    // is refused outright rather than sanitised into something plausible.
    if (segments.any { it == ".." }) return null
    val fileName = segments.last()

    if (segments.any { isAttachmentDirSegment(it) }) {
        // Qualified shape. Keep the path as written — it is already
        // relative to the page file's directory.
        return AttachmentRef(relativePath = segments.joinToString("/"), fileName = fileName)
    }
    if (segments.size == 1 && hasFileExtension(fileName)) {
        return AttachmentRef(
            relativePath = "${attachmentDirName(pageId)}/$fileName",
            fileName = fileName,
        )
    }
    return null
}

/** `.attachments.<pageId>` — the directory Collectives stores attachments in. */
fun attachmentDirName(pageId: Long): String = ".attachments.$pageId"

internal fun isAttachmentDirSegment(segment: String): Boolean = ATTACHMENT_DIR_PATTERN.matches(segment)

/**
 * True when [fileName]'s extension is one Markwon can actually render as an
 * inline image. Everything else — PDFs, office documents, archives, audio,
 * video — is a file to hand off to another app, not a bitmap to decode.
 */
fun isImageFileName(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTENSIONS

/**
 * True when [fileName] carries an extension we positively recognise as a
 * non-image file. Deliberately *not* `!isImageFileName(…)`: an unrecognised
 * extension is left as an image embed, so a format we haven't listed (a new
 * image codec, say) keeps rendering rather than silently degrading to a
 * link. Only types we're sure about get demoted.
 */
internal fun isKnownNonImageFile(fileName: String): Boolean {
    val ext = extensionOf(fileName)
    return ext in FILE_EXTENSIONS && ext !in IMAGE_EXTENSIONS
}

private fun hasFileExtension(fileName: String): Boolean = extensionOf(fileName) in FILE_EXTENSIONS

private fun extensionOf(fileName: String): String =
    fileName
        .substringAfterLast('.', "")
        .lowercase()

/**
 * Decode + tidy a relative markdown target: percent-decoding, `./` and
 * leading-`/` strip, query/fragment strip. Mirrors [decodeWikiTarget] minus
 * the `.md` handling (a `.md` target is a page, never an attachment).
 */
private fun cleanRelativePath(raw: String): String {
    // Same `+` pre-escape as decodeWikiTarget (B-34): URLDecoder is
    // form-decoding and would otherwise turn a literal `+` in a filename
    // into a space, so `my+notes.pdf` would stop resolving.
    val preEscaped = raw.replace("+", "%2B")
    val decoded = runCatching { URLDecoder.decode(preEscaped, "UTF-8") }.getOrElse { raw }
    return decoded
        .substringBefore('#')
        .substringBefore('?')
        .removePrefix("./")
        .removePrefix("/")
        .trim()
}

/**
 * Derive the page file's own directory URL from the `.attachments.<pageId>/`
 * base URL the page screens already resolve.
 *
 * Both bases share every segment but the last, so this avoids threading a
 * second URL through `MarkdownView` → `PageViewScreen` → `PageViewModel`
 * (and the editor's parallel chain) purely to resolve the qualified ref
 * shape. Returns null if [imageBaseUrl] isn't the expected shape, in which
 * case callers fall back to leaving the ref alone.
 */
internal fun pageDirectoryUrlFrom(imageBaseUrl: String): String? {
    val withoutTrailingSlash = imageBaseUrl.trimEnd('/')
    val lastSegment = withoutTrailingSlash.substringAfterLast('/')
    if (!isAttachmentDirSegment(lastSegment)) return null
    val parent = withoutTrailingSlash.substringBeforeLast('/', "")
    if (parent.isEmpty()) return null
    return "$parent/"
}

/**
 * Rewrites `![alt](file.pdf)` — image syntax pointing at something that
 * isn't an image — into a tappable link `[📄 alt](file.pdf)`.
 *
 * Nextcloud Text writes non-image attachments into the body, and Markwon's
 * `ImagesPlugin` will happily try to decode a PDF as a bitmap and leave a
 * blank slot where the file should be. Demoting to a link means the tap
 * lands in [handleMarkdownLink] instead, which routes it to the
 * download-and-open path. Runs *before* `absolutizeImageRefs` so the
 * demoted refs never acquire an image URL.
 *
 * Targets with a scheme (`http(s)://…`) are left alone: a remote PDF link
 * isn't a page attachment and Custom Tabs already handles it. So are targets
 * whose type we don't recognise — see [isKnownNonImageFile]. Refs inside
 * fenced blocks / inline code are skipped by the usual alternation.
 */
fun demoteNonImageEmbeds(markdown: String): String =
    EMBED_PATTERN.replace(markdown) { match ->
        // A null `image` group means the match landed on a fence or an
        // inline-code span — emit it verbatim.
        if (match.groups["image"] == null) return@replace match.value
        val target = match.groups["target"]?.value.orEmpty()
        val fileName = fileNameOf(target)
        if (target.isEmpty() || target.contains("://") || !isKnownNonImageFile(fileName)) {
            return@replace match.value
        }
        val alt = match.groups["alt"]?.value?.takeIf { it.isNotBlank() } ?: fileName
        val trailing = match.groups["trailing"]?.value.orEmpty()
        "[$FILE_GLYPH $alt]($target$trailing)"
    }

private fun fileNameOf(target: String): String =
    target
        .substringBefore('#')
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')

/** Document glyph prefixed to a demoted embed's label. */
private const val FILE_GLYPH = "📄"

private val ATTACHMENT_DIR_PATTERN = Regex("^\\.attachments\\.\\d+$")

// Same alternation strategy as `WIKILINK_PATTERN` / `IMAGE_REF_PATTERN`:
// fenced code → inline code → image embed. Earlier alternations win, so an
// embed inside a code segment is consumed by the code groups first and
// passes through untouched (B-4).
private val EMBED_PATTERN = Regex(
    "(?s)" +
        "(?<fence>```.*?```|~~~.*?~~~)" +
        "|(?<code>`[^`\\n]+`)" +
        "|(?<image>!\\[(?<alt>[^\\]]*)]\\((?<target>[^)\\s]+)(?<trailing>\\s+[^)]*)?\\))",
)

/** Extensions Markwon's `ImagesPlugin` can decode into an inline image. */
private val IMAGE_EXTENSIONS = setOf(
    "png",
    "jpg",
    "jpeg",
    "gif",
    "webp",
    "bmp",
    "svg",
    "avif",
    "heic",
    "heif",
    "ico",
    "tif",
    "tiff",
)

/**
 * Extensions that mark a bare single-segment target as a *file* rather than
 * a page title. Deliberately an allowlist: page titles contain dots often
 * enough that "anything after the last dot" would misfire. `md` is absent —
 * a `.md` target is always a page reference. Images are included so an
 * `[photo](photo.jpg)` *link* (as opposed to an embed) also opens.
 */
private val FILE_EXTENSIONS = IMAGE_EXTENSIONS +
    setOf(
        // documents
        "pdf",
        "doc",
        "docx",
        "odt",
        "rtf",
        "txt",
        "csv",
        "tsv",
        "xls",
        "xlsx",
        "ods",
        "ppt",
        "pptx",
        "odp",
        "odg",
        "epub",
        // archives
        "zip",
        "tar",
        "gz",
        "bz2",
        "xz",
        "7z",
        "rar",
        // audio / video
        "mp3",
        "m4a",
        "wav",
        "ogg",
        "oga",
        "opus",
        "flac",
        "aac",
        "mp4",
        "m4v",
        "webm",
        "mov",
        "mkv",
        "avi",
        // data / misc
        "json",
        "xml",
        "yaml",
        "yml",
        "log",
        "ics",
        "vcf",
    )
