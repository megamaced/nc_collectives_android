package com.megamaced.nccollectives.ui.screen.members

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.megamaced.nccollectives.data.api.HostInterceptor
import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import com.megamaced.nccollectives.domain.model.CollectiveMemberType
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ListStateSwitch
import com.megamaced.nccollectives.ui.components.memberAvatarUrl
import com.megamaced.nccollectives.ui.components.monogramOf

/**
 * What a Circles level is called on screen.
 *
 * `Unknown` gets wording that describes the *server's* silence rather than
 * the member: level 0 means the payload carried no level (or one this build
 * doesn't recognise), which is not the same as the member having no role.
 * "Member" would be a guess, and a guess that reads as a fact.
 */
internal fun memberRoleLabel(level: CollectiveMemberLevel): String =
    when (level) {
        CollectiveMemberLevel.Owner -> "Owner"
        CollectiveMemberLevel.Admin -> "Admin"
        CollectiveMemberLevel.Moderator -> "Moderator"
        CollectiveMemberLevel.Member -> "Member"
        CollectiveMemberLevel.Unknown -> "Role not reported"
    }

/**
 * What kind of thing a membership points at, or null when it is an ordinary
 * user account.
 *
 * Null for `User` on purpose: every row would otherwise be prefixed "User",
 * which is noise on the overwhelming majority of them and would bury the one
 * signal that matters — that *this* row is not a person. A team can contain
 * groups, bare email addresses, address-book contacts, other teams and
 * app-owned principals, and none of those have a face, a profile, or a
 * person behind them who can be asked anything.
 */
internal fun memberKindLabel(type: CollectiveMemberType): String? =
    when (type) {
        CollectiveMemberType.User -> null

        CollectiveMemberType.Group -> "Group"

        CollectiveMemberType.Circle -> "Team"

        CollectiveMemberType.Mail -> "Email address"

        CollectiveMemberType.Contact -> "Contact"

        CollectiveMemberType.App -> "App"

        // `CircleMemberDto.userType` defaults to 0, which is also the real
        // wire value for a single — so an absent field and a genuine single
        // are the same thing by the time they reach here, and naming either
        // specifically would be a guess. Both get the one fact a reader
        // needs from the row.
        CollectiveMemberType.Single, CollectiveMemberType.Unknown -> "Not a user account"
    }

/**
 * The second line of a row: the role, prefixed by the kind for anything that
 * isn't a user account. "Group · Moderator" says both of the things that row
 * needs to say, in the order they matter.
 */
internal fun memberSubtitle(member: CollectiveMember): String {
    val role = memberRoleLabel(member.level)
    val kind = memberKindLabel(member.type)
    return if (kind == null) role else "$kind · $role"
}

/**
 * Members of the Nextcloud Team backing a collective (B-90).
 *
 * Read-only, and deliberately so: adding, removing and re-levelling members
 * is a separate stage with its own surface (user-search endpoints, the
 * permission matrix for who may promote whom). Nothing on this screen hints
 * otherwise — there is no overflow menu and no per-row action, because a
 * disabled or absent control is easier to explain than one that 403s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembersScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: MembersViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Members", style = MaterialTheme.typography.titleMedium)
                        if (ui.collectiveName.isNotEmpty()) {
                            Text(
                                text = ui.collectiveName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            ListStateSwitch(
                isLoading = ui.isLoading,
                error = ui.errorMessage,
                isEmpty = ui.members.isEmpty(),
                // The null branch is the load-bearing one: `ErrorState` draws
                // no Retry button at all when there is nothing safe to retry,
                // which is how a 403 stops being something the user can
                // hammer — and hammering it throttles their own IP. See
                // `isRetryableFailure`.
                onRetry = if (ui.canRetry) viewModel::refresh else null,
                empty = {
                    EmptyState(
                        title = "No members listed",
                        message = "The server returned no members for this collective's team.",
                    )
                },
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // `id` and not `singleId`: the membership id is what is
                    // unique *within this circle*, which is the scope of this
                    // list. `singleId` is the cross-circle identity and is the
                    // right key for "same person elsewhere", not for a row.
                    items(ui.members, key = { it.id }) { member ->
                        MemberRow(member)
                        HorizontalDivider()
                    }
                    if (ui.mayHaveMore) {
                        item(key = TRUNCATION_ITEM_KEY) {
                            TruncationNotice(shown = ui.members.size)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: CollectiveMember) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemberAvatar(member)
        Column(modifier = Modifier.weight(1f)) {
            // `.label`, not `.displayName`: mail and contact memberships
            // routinely have no display name, and a row rendering as empty
            // space reads as a bug rather than as missing data.
            Text(text = member.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = memberSubtitle(member),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A face for a user account, a symbol for everything else.
 *
 * Non-user rows get no avatar at all rather than a generic one, because an
 * avatar is a claim: a circular portrait slot says "this is a person", and
 * for a group or a bare email address that is simply false. The symbol and
 * the kind label in [memberSubtitle] say the same thing twice on purpose —
 * the icon carries it at a glance, the text carries it for a screen reader.
 *
 * For real accounts the monogram is drawn *underneath* the image rather than
 * as a Coil error slot. It covers three cases with one mechanism: the fetch
 * in flight, a login with no uploaded avatar, and the JSON body Nextcloud
 * answers with for a user it doesn't recognise — that last one is a 404 with
 * a body Coil cannot decode as an image, so it fails silently and whatever
 * is behind it stays visible. Nothing needs to distinguish them.
 */
@Composable
private fun MemberAvatar(member: CollectiveMember) {
    val icon = nonPersonIcon(member.type)
    Box(
        modifier = Modifier
            .size(AVATAR_SIZE_DP.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                // Decorative: `memberSubtitle` already announces the kind,
                // and repeating it here would read it out twice per row.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AVATAR_ICON_SIZE_DP.dp),
            )
        } else {
            Text(
                text = monogramOf(member.label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (member.userId.isNotBlank()) {
                val url = remember(member.userId) { memberAvatarUrl(member.userId) }
                AsyncImage(
                    model = url,
                    // Decorative: the name is on the very next line.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * How the app says "there may be more of these" without a total.
 *
 * The endpoint returns no count, so a full page is the only evidence there
 * is and it is equally consistent with a team of exactly the limit. The
 * wording therefore claims a floor and not a number.
 */
@Composable
private fun TruncationNotice(shown: Int) {
    Text(
        text = "Showing the first $shown members. The server doesn't say how many more there are.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Symbol for a membership that isn't a user account; null for one that is. */
private fun nonPersonIcon(type: CollectiveMemberType): ImageVector? =
    when (type) {
        CollectiveMemberType.User -> null
        CollectiveMemberType.Group -> Icons.Filled.Group
        CollectiveMemberType.Circle -> Icons.Filled.Groups
        CollectiveMemberType.Mail -> Icons.Filled.AlternateEmail
        CollectiveMemberType.Contact -> Icons.Filled.Contacts
        CollectiveMemberType.App -> Icons.Filled.Apps
        CollectiveMemberType.Single, CollectiveMemberType.Unknown -> Icons.AutoMirrored.Filled.HelpOutline
    }

/**
 * `GET /index.php/avatar/{userId}/{size}` on the user's own Nextcloud.
 *
 * **S-27.** Built on Retrofit's placeholder host, which keeps `TokenStore`
 * out of the UI layer entirely: `HostInterceptor` recognises that host as
 * "a URL this app constructed", retargets it at the stored Nextcloud
 * (splicing in any subdirectory prefix), and stamps the `RequestOrigin` tag
 * that `AuthInterceptor` requires before it will attach Basic-auth. Coil
 * runs on that same shared authenticated client, so nothing else is needed
 * here — and, just as importantly, this URL is one the app built rather than
 * one that arrived in a server response, which is the distinction that
 * interceptor pair exists to enforce.
 *
 * The trade against `PageBodyService.resourceUrl`, which uses the *real*
 * host for its Coil URLs, is the cache key: this one does not name the
 * server. That is acceptable because the app holds one account at a time and
 * `LogoutHandler` clears both of Coil's caches on sign-out, so a second
 * account cannot read the first one's avatars back out of them.
 *
 * `Uri.encode` rather than interpolation, and for a concrete reason: real
 * login names on this server are email-shaped (`david@macemail.co.uk`) and a
 * federated member's is `user@host`, so an unencoded `@`, `/`, `?` or `#`
 * in a path segment addresses a different route or truncates the id (B-33 is
 * the same trap on tag names). `Uri.encode` is the path-encoding variant —
 * it percent-encodes rather than form-encoding a space to `+`.
 */
private const val AVATAR_SIZE_DP = 40

private const val AVATAR_ICON_SIZE_DP = 20

/**
 * Pixel size asked of the server, not the layout size. 128 covers a 40 dp
 * slot up to ~3x density without asking every phone for the 512 px original.
 * Fixed rather than derived from the display: Nextcloud caches avatars per
 * requested size, and one number for every device keeps both that cache and
 * Coil's keyed on one entry per user.
 */
private const val AVATAR_REQUEST_PX = 128

/**
 * Key for the truncation footer. A literal so it cannot collide with a
 * `CollectiveMember.id` — Lazy list keys share one namespace, and duplicates
 * throw at runtime rather than degrading.
 */
private const val TRUNCATION_ITEM_KEY = "members-truncation-notice"
