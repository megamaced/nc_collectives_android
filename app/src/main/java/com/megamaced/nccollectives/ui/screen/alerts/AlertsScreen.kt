package com.megamaced.nccollectives.ui.screen.alerts

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.AlertKind
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.SnackbarMessageEffect
import com.megamaced.nccollectives.ui.components.rememberNowMillis
import com.megamaced.nccollectives.ui.navigation.OnReselected
import com.megamaced.nccollectives.ui.navigation.scrollToTop
import com.megamaced.nccollectives.util.UiText
import com.megamaced.nccollectives.util.relativeTimeAgo
import com.megamaced.nccollectives.util.resolve
import com.megamaced.nccollectives.util.uiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertsScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    onOpenCollective: (Long) -> Unit,
    reselected: Flow<Unit> = emptyFlow(),
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val supported by viewModel.supported.collectAsStateWithLifecycle()
    val newIds by viewModel.newIds.collectAsStateWithLifecycle()
    val notifyCollectives by viewModel.notifyCollectives.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val opening by viewModel.opening.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val nowMillis by rememberNowMillis()
    var showMenu by remember { mutableStateOf(false) }
    var confirmDismissAll by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    OnReselected(reselected) { listState.scrollToTop() }

    SnackbarMessageEffect(viewModel.messages, snackbarHostState)
    // As on Home: a target fetched for an earlier visit, which the user has
    // since left, is dropped rather than pulling them away on their return.
    val visit = remember(viewModel) { viewModel.beginVisit() }
    val currentOnOpenPage by rememberUpdatedState(onOpenPage)
    val currentOnOpenCollective by rememberUpdatedState(onOpenCollective)
    LaunchedEffect(viewModel, visit) {
        viewModel.targets.collect { target ->
            if (target.visit != visit) return@collect
            when (target) {
                is AlertTarget.Page -> currentOnOpenPage(target.pageId)
                is AlertTarget.Collective -> currentOnOpenCollective(target.collectiveId)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    // Seen means shown with the tab in front: not merely composed behind
    // another screen of the Alerts stack.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(alerts, lifecycle) {
        val shown = alerts ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.onShown(shown) }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (!alerts.isNullOrEmpty()) {
                        IconButton(onClick = { confirmDismissAll = true }) {
                            Icon(Icons.Outlined.ClearAll, contentDescription = stringResource(R.string.alerts_dismiss_all))
                        }
                    }
                    if (notifyCollectives.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.alerts_notification_settings)) },
                                    onClick = {
                                        showMenu = false
                                        showSettings = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            AlertsContent(
                alerts = alerts,
                supported = supported,
                newIds = newIds,
                nowMillis = nowMillis,
                onOpen = { alert -> viewModel.open(alert, visit) },
                onDismiss = viewModel::dismiss,
                listState = listState,
            )
            // Fetching an alert's page before it can open.
            if (opening != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (confirmDismissAll) {
        val count = alerts?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmDismissAll = false },
            title = { Text(stringResource(R.string.alerts_dismiss_all_confirm_title)) },
            text = {
                Text(dismissAllConfirmation(count).resolve())
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDismissAll = false
                    viewModel.dismissAll()
                }) { Text(stringResource(R.string.alerts_dismiss_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmDismissAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showSettings) {
        NotificationSettingsSheet(
            collectives = notifyCollectives,
            onSetLevel = viewModel::setNotifyLevel,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * The list, or why there is none. [alerts] null is the cache not having
 * answered yet, and draws nothing.
 */
@Composable
internal fun AlertsContent(
    alerts: List<Alert>?,
    supported: Boolean?,
    newIds: Set<Long>,
    nowMillis: Long,
    onOpen: (Alert) -> Unit,
    onDismiss: (Long) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    when {
        supported == false -> {
            EmptyState(
                title = stringResource(R.string.alerts_unsupported_title),
                message = stringResource(R.string.alerts_unsupported_message),
            )
        }

        alerts == null -> {
            Box(Modifier.fillMaxSize())
        }

        alerts.isEmpty() -> {
            EmptyState(
                title = stringResource(R.string.alerts_empty_title),
                message = stringResource(R.string.alerts_empty_message),
            )
        }

        else -> {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(alerts, key = { it.id }) { alert ->
                    DismissibleAlertRow(
                        alert = alert,
                        isNew = alert.id in newIds || !alert.seen,
                        nowMillis = nowMillis,
                        onOpen = { onOpen(alert) },
                        onDismiss = { onDismiss(alert.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DismissibleAlertRow(
    alert: Alert,
    isNew: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val dismissLabel = stringResource(R.string.action_dismiss)
    SwipeToDismissBox(
        state = state,
        onDismiss = { onDismiss() },
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        // Swiping isn't something every user can do; the same action is
        // offered to accessibility services by name.
        modifier = Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(dismissLabel) {
                    onDismiss()
                    true
                },
            )
        },
    ) {
        AlertRow(alert = alert, isNew = isNew, nowMillis = nowMillis, onClick = onOpen)
    }
}

@Composable
private fun AlertRow(
    alert: Alert,
    isNew: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val opensSomething = alert.kind != AlertKind.PageDeleted || alert.collectiveId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = opensSomething, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = alert.kind.icon(),
            contentDescription = stringResource(alert.kind.label()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = alert.subject.ifBlank { fallbackSubject(alert).resolve() },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (alert.timestamp > 0) {
                Text(
                    text = relativeTimeAgo(alert.timestamp * 1_000L, nowMillis).resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isNew) {
            val newLabel = stringResource(R.string.alert_new)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = newLabel },
            )
        }
    }
}

private fun AlertKind.icon(): ImageVector =
    when (this) {
        AlertKind.Mention -> Icons.Outlined.AlternateEmail
        AlertKind.PageUpdated -> Icons.Outlined.Edit
        AlertKind.PageDeleted -> Icons.Outlined.DeleteOutline
    }

/** What dismissing every alert will do: "this alert" for one, a count for more. */
internal fun dismissAllConfirmation(count: Int): UiText =
    if (count == 1) uiText(R.string.alerts_dismiss_all_confirm_one) else UiText.Plural(R.plurals.alerts_dismiss_all_confirm, count)

/** The alert's kind, for screen readers. */
@StringRes
internal fun AlertKind.label(): Int =
    when (this) {
        AlertKind.Mention -> R.string.alert_kind_mention
        AlertKind.PageUpdated -> R.string.alert_kind_page_updated
        AlertKind.PageDeleted -> R.string.alert_kind_page_deleted
    }

/**
 * For a server that sends no subject: the same sentence, from the parameters.
 * Each combination of a named or unnamed page, with or without its
 * collective, is a whole sentence, so a language can inflect "a page" as
 * each sentence needs.
 */
internal fun fallbackSubject(alert: Alert): UiText {
    val who = alert.actorName?.let(UiText::Raw) ?: uiText(R.string.alert_someone)
    val sentences = when (alert.kind) {
        AlertKind.Mention -> {
            AlertSentences(
                titled = R.string.alert_mentioned,
                titledIn = R.string.alert_mentioned_in,
                untitled = R.string.alert_mentioned_untitled,
                untitledIn = R.string.alert_mentioned_untitled_in,
            )
        }

        AlertKind.PageUpdated -> {
            AlertSentences(
                titled = R.string.alert_changed,
                titledIn = R.string.alert_changed_in,
                untitled = R.string.alert_changed_untitled,
                untitledIn = R.string.alert_changed_untitled_in,
            )
        }

        AlertKind.PageDeleted -> {
            AlertSentences(
                titled = R.string.alert_deleted,
                titledIn = R.string.alert_deleted_in,
                untitled = R.string.alert_deleted_untitled,
                untitledIn = R.string.alert_deleted_untitled_in,
            )
        }
    }
    val title = alert.pageTitle
    val collective = alert.collectiveName
    return when {
        title != null && collective != null -> uiText(sentences.titledIn, who, title, collective)
        title != null -> uiText(sentences.titled, who, title)
        collective != null -> uiText(sentences.untitledIn, who, collective)
        else -> uiText(sentences.untitled, who)
    }
}

private class AlertSentences(
    @param:StringRes val titled: Int,
    @param:StringRes val titledIn: Int,
    @param:StringRes val untitled: Int,
    @param:StringRes val untitledIn: Int,
)
