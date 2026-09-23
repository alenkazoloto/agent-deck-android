package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewRequest
import dev.agentdeck.companion.data.AiReviewSheet

/**
 * Codex's `/review`, as the desk's dialog: what to review — uncommitted changes, a branch's
 * changes against another, or one commit — then the findings, P0 first, with the reviewer's
 * verdict in its own words. The run is the machine's; closing this leaves it going.
 */
@Composable
fun AiReviewDialog(
    sheet: AiReviewSheet,
    onDismiss: () -> Unit,
    onEdit: (kind: String?, branch: String?, commit: String?) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("ai-review-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text("Review with Codex", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                val state = sheet.state
                val running = state?.running == true
                // One scrolling body under fixed buttons: at 200% text the form alone outgrows the dialog.
                LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                    if (state == null) {
                        item(key = "reading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) }
                    } else {
                        item(key = "form") { Form(sheet, enabled = !running && !sheet.sending, onEdit) }
                        state.spendNote?.takeIf { !running }?.let { note ->
                            item(key = "spend") { Note(note) }
                        }
                        if (running) item(key = "running") {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("ai-review-running")) {
                                Text(
                                    if (state.stopping) "Stopping…" else "Reviewing ${state.target?.replaceFirstChar(Char::lowercase) ?: "the changes"}…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                                Text(
                                    "You can close this; the review keeps running on the machine.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        state.report?.takeIf { !running }?.let { report ->
                            item(key = "summary") {
                                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("ai-review-summary")) {
                                    state.target?.let {
                                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        listOfNotNull(report.summary, report.correctness?.replaceFirstChar(Char::uppercase)).joinToString("  ·  "),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (report.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    )
                                    report.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                                }
                            }
                            // By place: a reviewer can report the same title at the same line twice.
                            itemsIndexed(report.findings, key = { i, _ -> "finding-$i" }) { _, finding -> Finding(finding) }
                            if (report.omitted > 0) item(key = "omitted") {
                                Note(if (report.omitted == 1) "1 more finding is in the IDE's review." else "${report.omitted} more findings are in the IDE's review.")
                            }
                            report.message?.let { item(key = "message") { Note(it) } }
                        }
                    }
                }
                sheet.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("ai-review-error"),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    if (running) {
                        TextButton(onClick = onStop, enabled = !sheet.sending && state?.stopping != true, modifier = Modifier.testTag("ai-review-stop")) { Text("Stop") }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = state != null && !sheet.sending,
                            modifier = Modifier.padding(start = 8.dp).testTag("ai-review-start"),
                        ) { Text(if (state?.report != null) "Review again" else "Review") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Form(sheet: AiReviewSheet, enabled: Boolean, onEdit: (String?, String?, String?) -> Unit) {
    Column {
        Choice("Uncommitted changes", MobileAiReviewRequest.UNCOMMITTED, sheet.kind, enabled) { onEdit(it, null, null) }
        Choice("Changes against a branch", MobileAiReviewRequest.BASE, sheet.kind, enabled) { onEdit(it, null, null) }
        if (sheet.kind == MobileAiReviewRequest.BASE) {
            OutlinedTextField(
                value = sheet.branch,
                onValueChange = { onEdit(null, it, null) },
                label = { Text("Branch") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("ai-review-branch"),
            )
        }
        Choice("One commit", MobileAiReviewRequest.COMMIT, sheet.kind, enabled) { onEdit(it, null, null) }
        if (sheet.kind == MobileAiReviewRequest.COMMIT) {
            OutlinedTextField(
                value = sheet.commit,
                onValueChange = { onEdit(null, null, it) },
                label = { Text("Commit") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("ai-review-commit"),
            )
        }
    }
}

@Composable
private fun Choice(label: String, kind: String, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = kind == selected, enabled = enabled, role = Role.RadioButton) { onSelect(kind) }
            .padding(horizontal = 12.dp)
            .testTag("ai-review-kind-$kind"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = kind == selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Finding(finding: MobileAiReviewFinding) {
    var expanded by rememberSaveable(finding.path, finding.startLine, finding.title) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = finding.body.isNotBlank(), role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show the whole finding") { expanded = !expanded }
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .testTag("ai-review-finding"),
    ) {
        Text(
            listOfNotNull(finding.priorityLabel, finding.confidence?.let { "confidence $it%" }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = if (finding.priority != null && finding.priority <= 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(finding.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            finding.locationLabel,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (finding.body.isNotBlank()) {
            Text(
                finding.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}
