package com.megamaced.nccollectives.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Wall-clock time that re-emits every [intervalMs], so relative-time labels
 * drawn from it age while the screen stays open instead of freezing at
 * whatever they said when the list was first composed.
 *
 * A minute is the cadence the labels themselves change at — anything
 * finer would recompose for no visible difference, and the coarser units
 * ("3 hours ago") only ever gain accuracy from ticking more often than
 * they need to.
 *
 * Hoist this to the screen and pass the value down. One ticker per list is
 * the point; reading it inside a row composable would start a coroutine
 * per row.
 */
@Composable
fun rememberNowMillis(intervalMs: Long = 60_000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
