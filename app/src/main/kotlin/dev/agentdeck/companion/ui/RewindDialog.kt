package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileRewindPoint
import dev.agentdeck.companion.data.RewindPicker

/**
 * The desk's `/rewind` picker in its two steps: "Rewind to before…" one of your messages, then
 * what goes back with it. Newest first, as the fork picker: a phone goes back a turn or two far
 * more often than to the start. Each message says when it was sent and how many files its turn
 * changed — the count that decides whether "Restore code" is offered at all.
 */
@Composable
fun RewindDialog(
    picker: RewindPicker,
    onDismiss: () -> Unit,
    onChoose: (MobileRewindPoint) -> Unit,
    onScope: (MobileRewindPoint, String) -> Unit,
    onBack: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("rewind-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                val chosen = picker.chosen
                if (chosen == null) Targets(picker, onChoose) else Scopes(picker, chosen, onScope, onBack)
                picker.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("rewind-error"),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Targets(picker: RewindPicker, onChoose: (MobileRewindPoint) -> Unit) {
    Text("Rewind to before…", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
    val points = picker.points
    if (points == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
        return
    }
    Text(
        "Pick one of your messages. Next, choose whether the conversation, the code or both go back to just before it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
    )
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(points.points.asReversed(), key = { it.id }) { point ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Rewind to before this message") { onChoose(point) }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .testTag("rewind-point-${point.ordinal}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${point.ordinal}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(point.label.ifBlank { "(empty message)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    rewindMeta(point, System.currentTimeMillis())?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (points.omitted > 0) {
            item(key = "omitted") {
                Text(
                    if (points.omitted == 1) "1 older message is not listed here — rewind to it in the IDE."
                    else "${points.omitted} older messages are not listed here — rewind to them in the IDE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Scopes(picker: RewindPicker, point: MobileRewindPoint, onScope: (MobileRewindPoint, String) -> Unit, onBack: () -> Unit) {
    // Scrolls under a fixed Cancel: at 200% text the notes alone outgrow the dialog.
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { ScopeStep(picker, point, onScope, onBack) }
}

@Composable
private fun ScopeStep(picker: RewindPicker, point: MobileRewindPoint, onScope: (MobileRewindPoint, String) -> Unit, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        DeckIconButton(label = "Back to your messages", icon = Icons.Filled.ArrowBack, onClick = onBack, enabled = !picker.rewinding)
        Text("Rewind", style = MaterialTheme.typography.headlineSmall)
    }
    Text(
        "“${point.label}”",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    if (picker.rewinding) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
    // The facts every code restore owes, above the choice they can still change, as the desk's ad line.
    if (point.scopes.any { it != MobileRewindPoint.CONVERSATION }) {
        picker.points?.notes?.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
            )
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        point.scopes.forEach { scope ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = !picker.rewinding, role = Role.Button) { onScope(point, scope) }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .testTag("rewind-scope-$scope"),
            ) {
                Text(MobileRewindPoint.label(scope), style = MaterialTheme.typography.bodyLarge)
                scopeNote(point, scope)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "10:42 · 3 files" — when the message was sent, and what its turn changed. */
fun rewindMeta(point: MobileRewindPoint, nowMs: Long): String? = listOfNotNull(
    point.atMs?.let { Times.clock(it, nowMs) },
    point.files.takeIf { it > 0 }?.let { if (it == 1) "1 file" else "$it files" },
).joinToString(" · ").takeIf { it.isNotEmpty() }

/**
 * What a scope does to this message, said before the tap: the first message's conversation half
 * starts a new chat (the desk's inline note), and a code restore is previewed file by file next.
 */
fun scopeNote(point: MobileRewindPoint, scope: String): String? {
    val chat = "starts a new chat".takeIf { point.newChat && scope != MobileRewindPoint.CODE }
    val code = if (scope == MobileRewindPoint.CONVERSATION) null else when {
        point.undone > 1 -> "undoes ${point.undone} requests' file changes; you check each file next"
        else -> "you check each file next"
    }
    return listOfNotNull(chat, code).joinToString(" · ").takeIf { it.isNotEmpty() }
}
