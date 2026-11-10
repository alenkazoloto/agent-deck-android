package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import dev.agentdeck.companion.data.AiReviewLocation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * verdict in its own words. The run is the machine's; closing this leaves it going. A finding's
 * wrench asks the agent to fix it, as the desk marker's button does: the request joins the draft.
 * An opened finding offers the dialog's "Never report this", and "Review rules (N)…" edits the
 * project's list in place of the form, as the desk's Review Rules dialog does. A finding's place
 * opens its file's lines around it, the phone's form of the dialog's opening it in the editor.
 */
@Composable
fun AiReviewDialog(
    sheet: AiReviewSheet,
    onDismiss: () -> Unit,
    onEdit: (kind: String?, branch: String?, commit: String?) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    /** Adds the finding's fix request under the chat's draft; null hides the control. */
    onFix: ((MobileAiReviewFinding) -> Unit)? = null,
    onNeverReport: (MobileAiReviewFinding) -> Unit = {},
    onOpenRules: () -> Unit = {},
    onEditRules: (String) -> Unit = {},
    onSaveRules: () -> Unit = {},
    onCloseRules: () -> Unit = {},
    /** Opens finding `index` at its lines; null, from a machine that cannot, leaves the place as text. */
    onOpenLocation: ((Int, MobileAiReviewFinding) -> Unit)? = null,
    onCloseLocation: () -> Unit = {},
) {
    val rulesText = sheet.rulesText
    if (rulesText != null) return RulesEditor(sheet, rulesText, onEditRules, onSaveRules, onCloseRules)
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
                        // Null from a machine that keeps no rules for this chat: no control to fail.
                        state.rules?.let { rules ->
                            item(key = "rules") {
                                TextButton(
                                    onClick = onOpenRules,
                                    enabled = !sheet.sending,
                                    modifier = Modifier.padding(horizontal = 12.dp).testTag("ai-review-rules"),
                                ) { Text("Review rules (${rules.size})…") }
                            }
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
                            val learn = onNeverReport.takeIf { state.rules != null && !sheet.sending }
                            itemsIndexed(report.findings, key = { i, _ -> "finding-$i" }) { i, finding ->
                                Finding(finding, onFix, learn, onOpenLocation?.let { open -> { open(i, finding) } })
                            }
                            if (report.omitted > 0) item(key = "omitted") {
                                Note(if (report.omitted == 1) "1 more finding is in the IDE's review." else "${report.omitted} more findings are in the IDE's review.")
                            }
                            report.message?.let { item(key = "message") { Note(it) } }
                        }
                    }
                }
                sheet.notice?.let { Note(it, Modifier.testTag("ai-review-notice")) }
                ErrorLine(sheet.error)
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
    // Over the findings rather than in their place: Back returns to the list where it was scrolled
    // and opened, as the desk dialog's list keeps its selection while the editor shows the file.
    sheet.location?.let { LocationView(it, onBack = onCloseLocation, onDismiss = onDismiss) }
}

/** The desk's Review Rules dialog: the list as text, saved only on Save, so Cancel edits nothing. */
@Composable
private fun RulesEditor(sheet: AiReviewSheet, text: String, onEdit: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    // A stray tap outside must not throw typed rules away; Back and Cancel are the ways out.
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("ai-review-rules-editor"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text("Review rules", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Note(RULES_HINT)
                    OutlinedTextField(
                        value = text,
                        onValueChange = onEdit,
                        label = { Text("Rules") },
                        minLines = 4,
                        enabled = !sheet.sending,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("ai-review-rules-text"),
                    )
                }
                ErrorLine(sheet.error)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = onSave, enabled = !sheet.sending, modifier = Modifier.padding(start = 8.dp).testTag("ai-review-rules-save")) { Text("Save") }
                }
            }
        }
    }
}

/** The desk dialog's hint, word for word. */
const val RULES_HINT = "One rule per line. Every review in this project — manual or automatic — is held to them."

@Composable
private fun ErrorLine(error: String?) {
    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("ai-review-error"),
        )
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
private fun Finding(
    finding: MobileAiReviewFinding,
    onFix: ((MobileAiReviewFinding) -> Unit)?,
    onNeverReport: ((MobileAiReviewFinding) -> Unit)?,
    onOpenLocation: (() -> Unit)?,
) {
    var expanded by rememberSaveable(finding.path, finding.startLine, finding.title) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Top) {
    Column(
        Modifier.weight(1f)
            // Opening a finding is the desk's selecting it: its body and "Never report this" show.
            .clickable(enabled = finding.body.isNotBlank() || onNeverReport != null, role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show the whole finding") { expanded = !expanded }
            .padding(start = 24.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
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
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (onOpenLocation != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = if (onOpenLocation != null) TextDecoration.Underline else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onOpenLocation == null) Modifier else Modifier
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .clickable(role = Role.Button, onClickLabel = OPEN_LOCATION_LABEL, onClick = onOpenLocation)
                .testTag("ai-review-location"),
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
        if (expanded && onNeverReport != null) {
            TextButton(
                onClick = { onNeverReport(finding) },
                modifier = Modifier.padding(top = 4.dp).testTag("ai-review-never-report"),
            ) { Text(NEVER_REPORT_LABEL) }
        }
    }
    // The desk's marker button: the request goes under the draft and waits for Send.
    if (onFix != null && finding.fixPrompt.isNotEmpty()) {
        IconButton(onClick = { onFix(finding) }, modifier = Modifier.padding(top = 4.dp).testTag("ai-review-fix")) {
            Icon(Icons.Filled.Build, contentDescription = FIX_LABEL, tint = MaterialTheme.colorScheme.primary)
        }
    }
    }
}

/**
 * The finding's file around its lines, over the findings: the lines the reviewer named
 * marked and scrolled to, [MobileAiReviewExcerpt.CONTEXT_LINES] either side. Back returns to the
 * findings, as system Back does; Close closes the review.
 */
@Composable
private fun LocationView(location: AiReviewLocation, onBack: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("ai-review-location-view"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                val finding = location.finding
                Text(finding.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
                Text(
                    finding.locationLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                val excerpt = location.excerpt
                val lines = excerpt?.lines.orEmpty()
                when {
                    location.error != null -> ErrorLine(location.error)
                    excerpt == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
                    lines.isEmpty() -> Note(excerpt.message.orEmpty(), Modifier.testTag("ai-review-location-message"))
                    else -> {
                        // One line above the finding leads in; more would push it off the view at 200% text.
                        val list = rememberLazyListState(initialFirstVisibleItemIndex = (excerpt.startLine - excerpt.firstLine - 1).coerceIn(0, lines.lastIndex))
                        val width = (excerpt.firstLine + lines.lastIndex).toString().length
                        LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp).testTag("ai-review-location-lines"), state = list) {
                            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                                val number = excerpt.firstLine + i
                                val named = number in excerpt.startLine..excerpt.endLine
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (named) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                                        .padding(horizontal = 12.dp)
                                        .then(if (named) Modifier.testTag("ai-review-location-named") else Modifier),
                                ) {
                                    Text(
                                        number.toString().padStart(width),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("ai-review-location-back")) { Text("Back") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/** The accessible action on a finding's place. */
const val OPEN_LOCATION_LABEL = "Open the finding's lines"

/** The desk dialog's button, word for word. */
const val NEVER_REPORT_LABEL = "Never report this"

/** The desk's `ReviewFindingFix.CONTROL_NAME`, the control's accessible name. */
const val FIX_LABEL = "Ask the agent to fix this finding"

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}
