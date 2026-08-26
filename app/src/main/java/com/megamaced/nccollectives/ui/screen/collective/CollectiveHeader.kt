package com.megamaced.nccollectives.ui.screen.collective

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.HostInterceptor
import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.CollectiveMemberType
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.ui.components.AVATAR_REQUEST_PX
import com.megamaced.nccollectives.ui.components.memberAvatarUrl
import com.megamaced.nccollectives.ui.components.monogramOf
import okhttp3.HttpUrl

/**
 * Horizontal "Recent pages" strip rendered above the tree on
 * [PageTreeScreen]. Mirrors the widget Nextcloud's web client shows on the
 * collective landing page. Hidden when [pages] is empty.
 *
 * R-54: takes [PageListItem]s. A card shows an emoji, a title and a
 * relative timestamp — nothing that needs a body — so the strip is one of
 * the list consumers that must be incapable of reading one.
 */
@Composable
internal fun RecentPagesStrip(
    pages: List<PageListItem>,
    onPageClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Recent pages",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pages, key = { it.id }) { page ->
                RecentPageCard(page = page, onClick = { onPageClick(page.id) })
            }
        }
    }
}

@Composable
private fun RecentPageCard(
    page: PageListItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(132.dp)
            .height(132.dp)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
                style = MaterialTheme.typography.headlineSmall,
            )
            Column {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = relativeTime(page.serverTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Material card showing the collective's landing page (emoji + name + first
 * few lines of the body). Rendered above the tree on phone-sized screens
 * only — tablet/foldable layouts will get a proper two-pane view in a
 * future batch. Tap routes into the landing page.
 *
 * R-56: the one card on this screen that keeps a whole [Page]. It is the
 * only list-screen consumer that legitimately renders markdown, so it is
 * fed by a dedicated single-row flow (`observeLandingPage`) — widening the
 * tree's list projection to carry bodies for its sake would have handed a
 * body to all 200 rows to draw two lines on one of them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LandingPageCard(
    landing: Page,
    collectiveName: String,
    collectiveEmoji: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // R-45: [plainTextPreview] runs a chain of regexes over the landing
    // body to fill a two-line snippet. Key it on the body so it runs once
    // per fetched body rather than on every recomposition — `updateBody`
    // from `primeLandingBody` recomposes this card, as does every
    // unrelated `PageTreeUiState` write.
    val snippet = remember(landing.bodyMd) {
        landing.bodyMd?.let { plainTextPreview(it) }.orEmpty()
    }
    val displayName = collectiveName.ifBlank { landing.title }
    val displayEmoji = collectiveEmoji?.takeIf { it.isNotBlank() }
        ?: landing.emoji?.takeIf { it.isNotBlank() }
        ?: "🏠"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = displayEmoji, style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Welcome to $displayName",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (snippet.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap to open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Landing-page members strip — the avatars of the Nextcloud Team backing
 * this collective, plus one action that opens the members screen.
 *
 * A port of the web client's `MembersWidget.vue`, which is far smaller than
 * it sounds: avatars only. No count, no names, no role labels, no "+N more"
 * badge — the widget's template contains no text interpolation at all. The
 * only per-member text is a tooltip carrying the display name, which is
 * [TooltipBox] here (long-press on Android) and doubles as the TalkBack
 * label.
 *
 * Renders nothing at all when membership isn't addressable
 * ([MembersStripState.addressable] — the collective has no `circleId`).
 * That is not a permission decision: it means an older server never told us
 * which Team backs this collective, so every membership call would be aimed
 * at a route that can only fail. A strip whose sole possible content is an
 * error is worse than no strip.
 *
 * Collapsible, seeded from the server's per-user "show members" hint. The
 * web app persists the collapse through a Collectives `userSettings` route;
 * that is deliberately not implemented here, so the toggle is local to the
 * screen and the hint applies to the first render only.
 */
@Composable
internal fun MembersStrip(
    state: MembersStripState,
    onOpenMembers: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.addressable) return
    // `rememberSaveable` rather than `remember`: this sits in a `LazyColumn`
    // item, so plain `remember` state dies the moment the strip scrolls out
    // of view and the user's collapse would silently undo itself. Keyed on
    // the server hint so it seeds the *first* composition — the hint never
    // changes afterwards (nothing writes it back), so a later re-emission
    // can't fight a manual toggle.
    var expanded by rememberSaveable(state.showInitially) { mutableStateOf(state.showInitially) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Members",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                // Deliberately not "Show members" — that phrase belongs to
                // the action that opens the members screen, and two
                // controls a finger apart must not announce the same thing.
                contentDescription = if (expanded) "Collapse members" else "Expand members",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            MembersStripBody(state = state, onOpenMembers = onOpenMembers, onRetry = onRetry)
        }
    }
}

/**
 * The avatars-and-action row. Split out of [MembersStrip] so the collapsed
 * strip composes nothing but its own header.
 *
 * **B-85: loading is not emptiness.** Upstream derives its skeleton from
 * `loading = trimmedMembers.length === 0`, so a Team whose members can't be
 * read — the 403 case, which is the *normal* answer for a non-member —
 * shows three shimmering placeholder avatars forever. The four arms below
 * are the same shape `ListStateSwitch` uses for a whole screen (first-load
 * spinner, first-load error, empty, content — the non-content arms gated on
 * having nothing to show) but spelled out here rather than delegated,
 * because all three of that helper's arms are `fillMaxSize` full-screen
 * states and this is a 48dp row inside a `LazyColumn` item.
 */
@Composable
private fun MembersStripBody(
    state: MembersStripState,
    onOpenMembers: () -> Unit,
    onRetry: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // `maxWidth` here is already net of the padding above, and the
        // trailing action's width is reserved inside [visibleAvatarCount].
        // Measured rather than taken from `LocalConfiguration.screenWidthDp`
        // so split-screen and any future two-pane tablet layout get the
        // width of *this* strip and not of the display.
        val fits = visibleAvatarCount(
            containerWidthDp = maxWidth.value.toInt(),
            memberCount = state.members.size,
        )
        // Read out rather than smart-cast in the branch below: the arms read
        // more plainly, and a nullable property of another class is not
        // something to lean on the compiler for.
        val error = state.errorMessage
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading && state.members.isEmpty() -> MembersSkeleton()

                    error != null && state.members.isEmpty() -> MembersNotice(
                        text = error,
                        // No retry offered for a terminal failure, and 403
                        // is terminal — see [isRetryableFailure].
                        onRetry = if (state.canRetry) onRetry else null,
                    )

                    state.members.isEmpty() -> MembersNotice(
                        // Says nothing about membership. Circles answers 403
                        // identically for "you're not in this Team" and "no
                        // such Team", so no copy on this screen may assert
                        // either (B-84).
                        text = "No members to show.",
                        onRetry = null,
                    )

                    // Upstream renders nothing when the container is too
                    // narrow for a single slot. A `LazyRow` scrolls, so one
                    // avatar the user can scroll beats an empty strip.
                    else -> MemberAvatars(members = state.members.take(fits.coerceAtLeast(1)))
                }
            }
            IconButton(onClick = onOpenMembers) {
                Icon(
                    // Same destination either way — the label is the only
                    // thing the user's level changes, exactly as upstream
                    // switches on `level >= 8`. Nothing here is an
                    // authorisation check: the members screen asks the
                    // server, which is the only party that knows.
                    imageVector = if (state.canManage) {
                        Icons.Filled.ManageAccounts
                    } else {
                        Icons.Outlined.Groups
                    },
                    contentDescription = if (state.canManage) "Manage members" else "Show members",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemberAvatars(members: List<CollectiveMember>) {
    LazyRow(
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(AVATAR_GAP_DP.dp),
    ) {
        // `id` is the membership id — unique within the Team, and stable
        // across a re-fetch in a way `userId` isn't (a Team can hold the
        // same principal as both a user and a mail invite).
        items(members, key = { it.id }) { member ->
            MemberAvatar(member = member)
        }
    }
}

/**
 * One circular avatar with a display-name tooltip.
 *
 * Only a [CollectiveMemberType.User] has an avatar to fetch. A Team is not
 * a list of accounts — it can hold groups, bare email addresses, contacts,
 * other Teams and app-owned singles — and asking `/avatar/` about those
 * would 404 once per non-person member on every landing-page open, so they
 * get a type glyph instead of a network request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberAvatar(member: CollectiveMember) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(member.label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = Modifier
                .size(AVATAR_DP.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val typeGlyph = nonPersonGlyph(member.type)
            val avatarUrl = if (typeGlyph == null) memberAvatarUrl(member.userId) else null
            when {
                typeGlyph != null -> Icon(
                    imageVector = typeGlyph,
                    contentDescription = member.label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )

                avatarUrl == null -> MemberMonogram(member.label)

                // The avatar route answers a JSON 404 for a user the server
                // doesn't know — a federated member, or one deleted since
                // the Team row was written — and Coil surfaces that as an
                // error, not as an image. Both the error and the in-flight
                // states draw the monogram so the row never flashes an
                // empty circle.
                else -> SubcomposeAsyncImage(
                    model = avatarUrl,
                    contentDescription = member.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = { MemberMonogram(member.label) },
                    error = { MemberMonogram(member.label) },
                )
            }
        }
    }
}

@Composable
private fun MemberMonogram(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogramOf(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * Three placeholder circles, shown *only* while a fetch is actually in
 * flight — the distinction upstream loses (B-85).
 */
@Composable
private fun MembersSkeleton() {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(AVATAR_GAP_DP.dp),
    ) {
        repeat(SKELETON_AVATARS) {
            Box(
                modifier = Modifier
                    .size(AVATAR_DP.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/**
 * One quiet line where the avatars would be — the empty and failed arms.
 *
 * Inline rather than a snackbar on purpose: a 403 is the expected answer for
 * a non-member and would otherwise fire a snackbar on every single open of
 * that collective's landing page.
 */
@Composable
private fun MembersNotice(
    text: String,
    onRetry: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .heightIn(min = AVATAR_DP.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onRetry != null) {
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Retry")
            }
        }
    }
}

/**
 * A glyph for a membership that isn't a person, or null for
 * [CollectiveMemberType.User] — the only type with an avatar to fetch.
 *
 * Exhaustive rather than `else ->` so a member type added to
 * [CollectiveMemberType] later has to be classified here instead of
 * silently acquiring an avatar request it will never satisfy.
 */
private fun nonPersonGlyph(type: CollectiveMemberType): ImageVector? =
    when (type) {
        CollectiveMemberType.User -> null

        CollectiveMemberType.Mail -> Icons.Filled.AlternateEmail

        CollectiveMemberType.Contact -> Icons.Filled.ContactPage

        CollectiveMemberType.App -> Icons.Filled.Apps

        // Group, another Team nested in this one, a bare single-id, and a
        // type this build doesn't know: all "more than one person, or not
        // one we can name", which is as much as the app can honestly claim.
        CollectiveMemberType.Group,
        CollectiveMemberType.Circle,
        CollectiveMemberType.Single,
        CollectiveMemberType.Unknown,
        -> Icons.Filled.Group
    }

/** Diameter of one avatar. */
private const val AVATAR_DP = 40

/** Gap between two avatars. */
private const val AVATAR_GAP_DP = 8

/**
 * Width one avatar occupies in the strip, gap included. Upstream's slot is
 * `--default-clickable-area + 12` (44 + 12 CSS px); this is the same idea
 * on Material's 48dp grid, which also makes each slot a real touch target
 * for the long-press that raises the tooltip.
 */
private const val AVATAR_SLOT_DP = AVATAR_DP + AVATAR_GAP_DP

/**
 * Width held back for the trailing action. Upstream expresses this as the
 * `- 1` in `floor(containerWidth / slot) - 1`, which quietly assumes the
 * button is exactly one avatar wide; naming it as a width means the two can
 * be sized independently.
 */
private const val TRAILING_ACTION_DP = 48

/** Placeholder circles drawn while the fetch is in flight. */
private const val SKELETON_AVATARS = 3

/**
 * Hard cap on strip avatars, from upstream's `min(…, 15)`.
 *
 * Also the fetch limit — `PageTreeViewModel` passes this as
 * `listMembers(limit = …)` rather than `DEFAULT_MEMBER_LIMIT`, because the
 * strip renders no count and no "+N" badge, so a member past the 15th
 * cannot affect a single pixel of it. Keeping the two as one constant is
 * what makes that true by construction instead of by coincidence.
 */
internal const val MAX_STRIP_AVATARS = 15

/**
 * How many avatars fit, from upstream's
 * `min(members.length, floor(containerWidth / (clickableArea + 12)) - 1, 15)`.
 *
 * Pure so the three edges that actually bite are unit-testable without a
 * composition: a container that hasn't been measured yet (width 0), one too
 * narrow for a single slot once the trailing action has its width, and a
 * large Team hitting the 15 cap.
 *
 * Returns 0 for an unusable width. The caller decides what to draw then —
 * [MembersStripBody] shows one scrollable avatar rather than upstream's
 * nothing.
 */
internal fun visibleAvatarCount(
    containerWidthDp: Int,
    memberCount: Int,
    slotWidthDp: Int = AVATAR_SLOT_DP,
    reservedWidthDp: Int = TRAILING_ACTION_DP,
    maxAvatars: Int = MAX_STRIP_AVATARS,
): Int {
    if (memberCount <= 0 || slotWidthDp <= 0) return 0
    val usable = containerWidthDp - reservedWidthDp
    if (usable < slotWidthDp) return 0
    return minOf(memberCount, usable / slotWidthDp, maxAvatars)
}

/**
 * Render a server-timestamp (seconds since epoch) as a relative-time label
 * like "5 minutes ago". Falls back to an absolute date if the value can't
 * be turned into a sensible label.
 */
private fun relativeTime(serverTimestampSeconds: Long): String {
    if (serverTimestampSeconds <= 0L) return ""
    val millis = serverTimestampSeconds * 1_000L
    val span = DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    return span.toString()
}

// R-45: hoisted out of [plainTextPreview] so the seven patterns compile
// once at class-init instead of once per call — the preview sits on the
// landing card's recomposition path.
private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`[^`]*`")
private val IMAGE_REF = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
private val TEXT_LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
private val LINE_LEAD_MARKERS = Regex("(?m)^[#>*\\-+\\s]{1,6}")
private val EMPHASIS_MARKERS = Regex("[*_~]{1,3}")
private val WHITESPACE_RUN = Regex("\\s+")

private const val CODE_FENCE = "```"

/**
 * Longest prefix of the body [plainTextPreview] scans (R-45). The snippet
 * feeds a `maxLines = 2` `Text`, roughly 80 characters — scanning a
 * multi-kilobyte wiki page to fill it is pure waste.
 */
private const val PREVIEW_SCAN_LIMIT = 1500

/**
 * Strip common markdown markers for a short preview snippet — not a full
 * renderer, just enough to avoid showing `## Heading`, `**bold**`, or
 * `[text](url)` literal syntax in the card. Collapses whitespace.
 *
 * `internal` for `LandingPreviewTest`, which pins the output against the
 * R-45 rewrite.
 */
internal fun plainTextPreview(markdown: String): String {
    val capped = capForPreview(markdown)
    val preview = stripMarkdown(capped)
    // The cap can swallow the lot when a body opens with a code block
    // longer than [PREVIEW_SCAN_LIMIT] — the text after it is what the
    // uncapped scan would have shown. Rare enough to pay for a full scan.
    return if (preview.isEmpty() && capped.length < markdown.length) {
        stripMarkdown(markdown)
    } else {
        preview
    }
}

private fun stripMarkdown(markdown: String): String {
    var s = markdown
    // Drop fenced code blocks entirely.
    s = s.replace(FENCED_CODE, " ")
    // Drop inline code.
    s = s.replace(INLINE_CODE, " ")
    // Replace [text](url) with text.
    s = s.replace(IMAGE_REF, " ")
    s = s.replace(TEXT_LINK, "$1")
    // Strip leading heading hashes / bullets / blockquote markers.
    s = s.replace(LINE_LEAD_MARKERS, "")
    // Bold / italic / strike markers.
    s = s.replace(EMPHASIS_MARKERS, "")
    // Collapse whitespace.
    s = s.replace(WHITESPACE_RUN, " ").trim()
    return s
}

/**
 * Trim the body to [PREVIEW_SCAN_LIMIT] without leaving a half-written
 * markdown construct behind for [stripMarkdown] to miss.
 *
 * Two guards: cut back to the last line break (a truncated `[text](url`
 * or `**bold` would otherwise survive as literal syntax), and drop a
 * dangling code fence — an odd fence count means the cap landed inside a
 * code block whose opener no longer has a closer for [FENCED_CODE] to
 * pair with, so the code itself would leak into the snippet.
 */
private fun capForPreview(markdown: String): String {
    if (markdown.length <= PREVIEW_SCAN_LIMIT) return markdown
    val head = markdown.take(PREVIEW_SCAN_LIMIT)
    val lastBreak = head.lastIndexOf('\n')
    val whole = if (lastBreak > 0) head.substring(0, lastBreak) else head
    val fences = whole.split(CODE_FENCE).size - 1
    return if (fences % 2 == 1) whole.substringBeforeLast(CODE_FENCE) else whole
}
