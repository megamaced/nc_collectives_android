package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the attachment-reference classifier and the non-image
 * embed demotion.
 *
 * These pin the fix for the upstream Collectives maintainer's report that
 * PDFs and office files "don't work well" in the native viewer (GitHub
 * issue #1): before this, every scheme-less target — filename or
 * `.attachments.<id>/` path alike — fell through to the wiki-page *title*
 * resolver and surfaced `Linked page "…" not found`.
 *
 * The classifier is deliberately conservative, so the negative cases below
 * (page titles that merely contain a dot) matter as much as the positives.
 */
class AttachmentRefsTest {
    private val pageId = 42L

    // ---- parseAttachmentRef: qualified shape ----

    @Test
    fun parse_qualifiedPath_keepsPathVerbatim() {
        val ref = parseAttachmentRef(".attachments.1234/report.pdf", pageId)
        assertEquals(".attachments.1234/report.pdf", ref?.relativePath)
        assertEquals("report.pdf", ref?.fileName)
    }

    @Test
    fun parse_qualifiedPath_forAnotherPage_isNotRewrittenToOurs() {
        // A body copied between pages can name a folder that isn't ours. The
        // ref is relative to *this* page's directory either way, so keeping
        // it verbatim is the only resolution that can be right.
        val ref = parseAttachmentRef(".attachments.999/spec.docx", pageId)
        assertEquals(".attachments.999/spec.docx", ref?.relativePath)
    }

    @Test
    fun parse_qualifiedPath_needsNoKnownExtension() {
        // The `.attachments.N` segment is authoritative — an extension-less
        // file inside it is still an attachment.
        val ref = parseAttachmentRef(".attachments.7/Makefile", pageId)
        assertEquals(".attachments.7/Makefile", ref?.relativePath)
        assertEquals("Makefile", ref?.fileName)
    }

    @Test
    fun parse_leadingDotSlashAndEncoding_areNormalised() {
        val ref = parseAttachmentRef("./.attachments.1/My%20Report.pdf", pageId)
        assertEquals(".attachments.1/My Report.pdf", ref?.relativePath)
        assertEquals("My Report.pdf", ref?.fileName)
    }

    // ---- parseAttachmentRef: bare filename shape ----

    @Test
    fun parse_bareFilename_expandsAgainstOwnAttachmentDir() {
        val ref = parseAttachmentRef("report.pdf", pageId)
        assertEquals(".attachments.42/report.pdf", ref?.relativePath)
        assertEquals("report.pdf", ref?.fileName)
    }

    @Test
    fun parse_bareImageFilename_isAlsoAnAttachment() {
        // An image reached as a *link* rather than an embed should open too.
        assertEquals(".attachments.42/photo.jpg", parseAttachmentRef("photo.jpg", pageId)?.relativePath)
    }

    @Test
    fun parse_bareFilename_percentEncodedSpace() {
        assertEquals(
            ".attachments.42/Q3 numbers.xlsx",
            parseAttachmentRef("Q3%20numbers.xlsx", pageId)?.relativePath,
        )
    }

    @Test
    fun parse_bareFilename_preservesLiteralPlus() {
        // Same B-34 hazard decodeWikiTarget guards: URLDecoder is
        // form-decoding, so a literal `+` would become a space and the
        // filename would stop resolving server-side.
        assertEquals(
            ".attachments.42/notes+draft.pdf",
            parseAttachmentRef("notes+draft.pdf", pageId)?.relativePath,
        )
    }

    @Test
    fun parse_queryAndFragment_areStripped() {
        assertEquals(".attachments.42/report.pdf", parseAttachmentRef("report.pdf?v=2", pageId)?.relativePath)
        assertEquals(".attachments.42/report.pdf", parseAttachmentRef("report.pdf#page=3", pageId)?.relativePath)
    }

    // ---- parseAttachmentRef: things that are NOT attachments ----

    @Test
    fun parse_wikiPageTitle_isNotAnAttachment() {
        assertNull(parseAttachmentRef("Some Other Page", pageId))
    }

    @Test
    fun parse_markdownPageRef_isNotAnAttachment() {
        // `.md` is absent from the extension allowlist on purpose.
        assertNull(parseAttachmentRef("Other Page.md", pageId))
        assertNull(parseAttachmentRef("./Other%20Page.md", pageId))
    }

    @Test
    fun parse_pageTitleContainingDots_isNotAnAttachment() {
        // The reason the classifier uses an allowlist instead of "has a dot":
        // these are real page titles, and treating them as files would break
        // in-app wiki navigation.
        assertNull(parseAttachmentRef("Release 2.4", pageId))
        assertNull(parseAttachmentRef("v1.0.0", pageId))
        assertNull(parseAttachmentRef("Mr. Smith", pageId))
    }

    @Test
    fun parse_multiSegmentPathWithoutAttachmentDir_isNotAnAttachment() {
        // Only single-segment bare filenames are expanded; a nested path
        // that doesn't name an attachment folder is left to the page
        // resolver rather than guessed at.
        assertNull(parseAttachmentRef("folder/report.pdf", pageId))
    }

    @Test
    fun parse_traversalAttempt_isRefused() {
        assertNull(parseAttachmentRef("../../../secrets.pdf", pageId))
        assertNull(parseAttachmentRef(".attachments.1/../../escape.pdf", pageId))
    }

    @Test
    fun parse_emptyTarget_isNull() {
        assertNull(parseAttachmentRef("", pageId))
        assertNull(parseAttachmentRef("   ", pageId))
    }

    // ---- isImageFileName ----

    @Test
    fun isImageFileName_recognisesCommonImageTypes() {
        assertTrue(isImageFileName("photo.jpg"))
        assertTrue(isImageFileName("photo.JPEG"))
        assertTrue(isImageFileName("diagram.svg"))
        assertTrue(isImageFileName("shot.webp"))
    }

    @Test
    fun isImageFileName_rejectsDocuments() {
        assertFalse(isImageFileName("report.pdf"))
        assertFalse(isImageFileName("sheet.xlsx"))
        assertFalse(isImageFileName("archive.zip"))
        assertFalse(isImageFileName("Makefile"))
    }

    // ---- demoteNonImageEmbeds ----

    @Test
    fun demote_nonImageEmbed_becomesLink() {
        assertEquals(
            "[📄 report.pdf](report.pdf)",
            demoteNonImageEmbeds("![report.pdf](report.pdf)"),
        )
    }

    @Test
    fun demote_nonImageEmbed_keepsAltTextAsLabel() {
        assertEquals(
            "[📄 Q3 results](.attachments.9/q3.xlsx)",
            demoteNonImageEmbeds("![Q3 results](.attachments.9/q3.xlsx)"),
        )
    }

    @Test
    fun demote_emptyAlt_fallsBackToFileName() {
        assertEquals(
            "[📄 q3.xlsx](.attachments.9/q3.xlsx)",
            demoteNonImageEmbeds("![](.attachments.9/q3.xlsx)"),
        )
    }

    @Test
    fun demote_imageEmbed_isLeftAlone() {
        assertEquals(
            "![photo](photo.jpg)",
            demoteNonImageEmbeds("![photo](photo.jpg)"),
        )
    }

    @Test
    fun demote_remoteUrl_isLeftAlone() {
        // A remote PDF isn't a page attachment; Custom Tabs already handles
        // it and rewriting would change what the tap does.
        val input = "![doc](https://example.com/a.pdf)"
        assertEquals(input, demoteNonImageEmbeds(input))
    }

    @Test
    fun demote_titleAttribute_isPreserved() {
        assertEquals(
            "[📄 doc](report.pdf \"A title\")",
            demoteNonImageEmbeds("![doc](report.pdf \"A title\")"),
        )
    }

    @Test
    fun demote_insideFencedCodeBlock_isLeftAlone() {
        val input =
            """
            Example:
            ```
            ![report.pdf](report.pdf)
            ```
            Done
            """.trimIndent()
        assertEquals(input, demoteNonImageEmbeds(input))
    }

    @Test
    fun demote_insideInlineCode_isLeftAlone() {
        val input = "Write `![x](x.pdf)` to embed."
        assertEquals(input, demoteNonImageEmbeds(input))
    }

    @Test
    fun demote_extensionlessEmbed_isLeftAlone() {
        // No extension → can't tell → leave the author's intent alone rather
        // than demoting a possibly-valid image reference.
        val input = "![diagram](diagram)"
        assertEquals(input, demoteNonImageEmbeds(input))
    }

    @Test
    fun demote_multipleEmbeds_onlyNonImagesChange() {
        assertEquals(
            "![a](a.png) and [📄 b.pdf](b.pdf)",
            demoteNonImageEmbeds("![a](a.png) and ![b.pdf](b.pdf)"),
        )
    }

    // ---- pageDirectoryUrlFrom ----

    @Test
    fun pageDirectoryUrl_stripsAttachmentsSegment() {
        assertEquals(
            "https://nc.example/remote.php/dav/files/dave/.Collectives/Wiki/Folder/",
            pageDirectoryUrlFrom(
                "https://nc.example/remote.php/dav/files/dave/.Collectives/Wiki/Folder/.attachments.12/",
            ),
        )
    }

    @Test
    fun pageDirectoryUrl_toleratesMissingTrailingSlash() {
        assertEquals(
            "https://nc.example/dav/Wiki/",
            pageDirectoryUrlFrom("https://nc.example/dav/Wiki/.attachments.3"),
        )
    }

    @Test
    fun pageDirectoryUrl_unexpectedShape_isNull() {
        // Caller falls back to the attachments base, i.e. never worse than
        // the pre-existing behaviour.
        assertNull(pageDirectoryUrlFrom("https://nc.example/dav/Wiki/"))
        assertNull(pageDirectoryUrlFrom(".attachments.4/"))
    }
}
