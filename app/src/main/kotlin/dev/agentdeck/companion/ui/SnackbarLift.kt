package dev.agentdeck.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The bottom controls a snackbar must stay above, by owner. The Scaffold lifts it over a
 * navigation bar only; a conversation has none, so an Undo or "Showing <machine>" snackbar covered
 * the composer and took the tap meant for Send (J13). Keyed by owner because a screen transition
 * keeps the outgoing composer composed for a moment, and its dispose must not clear the incoming one.
 */
val LocalSnackbarLift = staticCompositionLocalOf<SnapshotStateMap<Any, Dp>> { SnapshotStateMap() }

fun SnapshotStateMap<Any, Dp>.height(): Dp = values.maxOrNull() ?: 0.dp

/** Marks the composer or action bar a snackbar must stay above, for as long as it is shown. */
@Composable
fun Modifier.liftsSnackbar(): Modifier {
    val lift = LocalSnackbarLift.current
    val owner = remember { Any() }
    val density = LocalDensity.current
    DisposableEffect(lift, owner) { onDispose { lift.remove(owner) } }
    return onSizeChanged { lift[owner] = with(density) { it.height.toDp() } }
}
