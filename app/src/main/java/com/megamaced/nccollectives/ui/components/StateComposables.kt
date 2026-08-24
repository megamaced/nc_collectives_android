package com.megamaced.nccollectives.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Scrollable despite always fitting: `Modifier.verticalScroll`
            // is what hands unconsumed drag to an enclosing
            // `PullToRefreshBox`. Without it the pull gesture is dead on
            // exactly the screens where refreshing matters most — an empty
            // list is the one you most want to retry (B-58).
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Same reason as EmptyState: keeps pull-to-refresh alive.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Retry")
            }
        }
    }
}

/**
 * The loading / error / empty / content switch a list screen shows (R-63).
 *
 * All three non-content arms are gated on [isEmpty] — that is what makes
 * them *first-load* states: once there are rows, a refresh in flight is a
 * spinner over the list and a failure is a snackbar, not a blank screen
 * where the user's data was. [isEmpty] is passed in rather than derived from
 * a list because a screen's "has content" test can be wider than one
 * collection: the page tree has content when it has a landing page, even
 * with no tree rows.
 */
@Composable
fun ListStateSwitch(
    isLoading: Boolean,
    error: String?,
    isEmpty: Boolean,
    onRetry: (() -> Unit)?,
    empty: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        isLoading && isEmpty -> LoadingState()
        error != null && isEmpty -> ErrorState(message = error, onRetry = onRetry)
        isEmpty -> empty()
        else -> content()
    }
}
