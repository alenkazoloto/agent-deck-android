package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileReleaseNotes

/**
 * The desk's `/release-notes` opened from a chat's `/` popup: Claude Code's changelog as the machine's
 * cache holds it, newest first, opening on the installed build as the desk's window does.
 *
 * Read each time the sheet opens. The notice above the list is the machine's own sentence for an empty
 * or behind cache — this app never fetches a changelog, and neither does the desk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReleaseNotesSheet(onLoad: suspend () -> MobileReleaseNotes?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileReleaseNotes?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("release-notes-sheet")) {
            Text("Claude Code release notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val notes = loaded
            when {
                loading -> ReleaseNotesNote("Reading the release notes…")
                notes == null -> ReleaseNotesNote("The machine did not answer. Close this and try again.")
                else -> ReleaseNotesBody(notes)
            }
        }
    }
}

@Composable
private fun ReleaseNotesBody(notes: MobileReleaseNotes) {
    val state = rememberLazyListState()
    val installedAt = notes.releases.indexOfFirst { it.version == notes.installed }
    // The row someone opening this came for; the notice item above the list shifts every index by one.
    LaunchedEffect(installedAt) {
        if (installedAt > 0) state.scrollToItem(installedAt + if (notes.notice != null) 1 else 0)
    }
    LazyColumn(Modifier.testTag("release-notes-list"), state = state) {
        notes.notice?.let { item(key = "notice") { ReleaseNotesNote(it) } }
        items(notes.releases.size, key = { notes.releases[it].version }) { index ->
            val release = notes.releases[index]
            Column(Modifier.testTag("release-notes-${release.version}").padding(top = 12.dp)) {
                val title = if (release.version == notes.installed) "${release.version} · installed" else release.version
                Text(title, style = MaterialTheme.typography.titleSmall)
                release.notes.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ReleaseNotesNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
