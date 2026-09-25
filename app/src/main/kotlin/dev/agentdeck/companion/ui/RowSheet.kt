package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit as OutlinedEdit
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionDeletePreview
import com.github.claudeagents.core.mobile.MobileForkPoint
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import com.github.claudeagents.core.mobile.MobileSessionForkPoints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import dev.agentdeck.companion.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions

/**
 * Long-press a row and everything about it is here: what can be done to it, and the metadata
 * the card has no width for.
 *
 * "Mark reviewed" appears here only on a "Done, unreviewed" row of a machine that advertises
 * `review`; "Open on desktop" is still absent, because the bridge serves no focus route. A sheet
 * item that silently did nothing would be worse than its absence — the app would be claiming a
 * capability the machine has not advertised.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowSheet(
    row: MobileFleetRow,
    group: FleetGroup,
    generatedAtMs: Long,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
    canReview: Boolean = false,
    onMarkReviewed: () -> Unit = {},
    /** The machine advertises `session-actions`: pin, Done and rename reach the desk's stores. */
    canOrganize: Boolean = false,
    onSessionAction: (action: String, title: String?) -> Unit = { _, _ -> },
    /** Opens [RenameDialog] once the sheet has gone; a dialog stacked on the sheet never settles. */
    onRename: () -> Unit = {},
    /** The machine advertises `session-export`: Share hands the desk's export text to Android. */
    canShare: Boolean = false,
    onShare: () -> Unit = {},
    /** The machine advertises `session-delete`: asks the machine what deleting would do, then confirms. */
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    /** The machine advertises `session-folders`; [folderName] is the desk folder holding the row. */
    canFolder: Boolean = false,
    folderName: String? = null,
    /** Opens [FolderDialog] once the sheet has gone, for [RenameDialog]'s reason. */
    onFolder: () -> Unit = {},
    /** The machine advertises `session-fork`: a Claude chat opens the message picker, a Codex chat forks at once. */
    canFork: Boolean = false,
    onFork: () -> Unit = {},
    /** The machine advertises `session-branch`: a Claude chat also copies whole, as the desk's Branch chat. */
    canBranch: Boolean = false,
    onBranch: () -> Unit = {},
    /** The machine advertises `pin-order`; [paintedPins] is how many pins the list paints beside this one. */
    canPinOrder: Boolean = false,
    paintedPins: Int = 0,
    onMovePin: (delta: Int) -> Unit = {},
    /** The machine advertises `session-retitle`: a model turn on the machine writes a new name. */
    canRetitle: Boolean = false,
    onRetitle: () -> Unit = {},
    /** The machine advertises `session-rewind`: a Claude chat opens the desk's `/rewind` picker. */
    canRewind: Boolean = false,
    onRewind: () -> Unit = {},
    /** The machine advertises `commit-staged`: the desk's "Commit staged changes…" over the chat's checkout. */
    canCommitStaged: Boolean = false,
    onCommitStaged: () -> Unit = {},
    /** The machine advertises `context-breakdown`: opens the desk gauge's grid over this chat. */
    canContext: Boolean = false,
    onContext: () -> Unit = {},
    /** The machine advertises `session-dirs` (a phone holds the permission grant): the chat's `--add-dir` list. */
    canDirs: Boolean = false,
    onDirs: () -> Unit = {},
    /** The machine advertises `session-mcp` (a phone holds the permission grant): which MCP servers a Claude chat may use. */
    canMcp: Boolean = false,
    onMcp: () -> Unit = {},
    /** The machine advertises `session-spend` (a phone holds the permission grant): this chat's own spend limits. */
    canSpend: Boolean = false,
    onSpend: () -> Unit = {},
    /** The machine advertises `session-handoff` and lists another Claude account: opens [HandoffDialog] once the sheet has gone. */
    canHandoff: Boolean = false,
    onHandoff: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        // Scrolls: a pinned row's full set plus its details outgrows a phone at large text.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(row.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    rowAnnouncement(row, group),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            RowActions.of(
                group, canReview, row, canOrganize, canShare, canDelete, canFolder, canFork,
                canPinOrder = canPinOrder, paintedPins = paintedPins, canRetitle = canRetitle, canBranch = canBranch,
                canRewind = canRewind, canCommitStaged = canCommitStaged, canContext = canContext, canDirs = canDirs, canMcp = canMcp, canSpend = canSpend, canHandoff = canHandoff,
            ).forEach { action ->
                when (action) {
                    RowAction.OPEN -> SheetAction(Icons.Filled.Edit, action.label, onOpen)
                    RowAction.STOP -> SheetAction(Icons.Filled.Close, action.label, onStop)
                    RowAction.SNOOZE -> SheetAction(Icons.Filled.DateRange, action.label, onSnooze)
                    RowAction.MARK_REVIEWED -> SheetAction(Icons.Filled.Check, action.label) {
                        onMarkReviewed()
                        onDismiss()
                    }
                    RowAction.PIN, RowAction.UNPIN, RowAction.DONE, RowAction.REOPEN ->
                        SheetAction(organizeIcon(action), action.label) {
                            onSessionAction(requestFor(action), null)
                            onDismiss()
                        }
                    RowAction.RENAME -> SheetAction(Icons.Outlined.OutlinedEdit, action.label) {
                        onDismiss()
                        onRename()
                    }
                    RowAction.MOVE_PIN_UP, RowAction.MOVE_PIN_DOWN -> SheetAction(
                        if (action == RowAction.MOVE_PIN_UP) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        action.label,
                    ) {
                        onMovePin(if (action == RowAction.MOVE_PIN_UP) -1 else 1)
                        onDismiss()
                    }
                    RowAction.RETITLE -> SheetAction(painterResource(R.drawable.ic_autorenew), action.label) {
                        onRetitle()
                        onDismiss()
                    }
                    RowAction.FOLDER -> SheetAction(painterResource(R.drawable.ic_folder), action.label) {
                        onDismiss()
                        onFolder()
                    }
                    RowAction.SHARE -> SheetAction(Icons.Filled.Share, action.label) {
                        onDismiss()
                        onShare()
                    }
                    RowAction.FORK -> SheetAction(painterResource(R.drawable.ic_fork), action.label) {
                        onDismiss()
                        onFork()
                    }
                    RowAction.BRANCH -> SheetAction(painterResource(R.drawable.ic_fork), action.label) {
                        onDismiss()
                        onBranch()
                    }
                    RowAction.HANDOFF -> SheetAction(Icons.Filled.AccountCircle, action.label) {
                        onDismiss()
                        onHandoff()
                    }
                    RowAction.REWIND -> SheetAction(Icons.Filled.Refresh, action.label) {
                        onDismiss()
                        onRewind()
                    }
                    RowAction.COMMIT_STAGED -> SheetAction(Icons.Filled.CheckCircle, action.label) {
                        onDismiss()
                        onCommitStaged()
                    }
                    RowAction.CONTEXT -> SheetAction(Icons.Filled.Info, action.label) {
                        onDismiss()
                        onContext()
                    }
                    RowAction.DIRS -> SheetAction(Icons.Filled.Add, action.label) {
                        onDismiss()
                        onDirs()
                    }
                    RowAction.MCP -> SheetAction(Icons.Filled.Settings, action.label) {
                        onDismiss()
                        onMcp()
                    }
                    RowAction.SPEND -> SheetAction(Icons.Filled.Settings, action.label) {
                        onDismiss()
                        onSpend()
                    }
                    RowAction.DELETE -> SheetAction(Icons.Filled.Delete, action.label) {
                        onDismiss()
                        onDelete()
                    }
                    RowAction.COPY_TITLE -> SheetAction(painterResource(R.drawable.ic_content_copy), action.label) {
                        clipboard.setText(AnnotatedString(row.title))
                        onDismiss()
                    }
                }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Meta("Project", row.projectPath)
                folderName?.let { Meta("Folder", it) }
                row.gitBranch?.takeIf { it.isNotBlank() }?.let { Meta("Branch", it) }
                Meta("Agent", row.vendor.label())
                row.model?.takeIf { it.isNotBlank() }?.let { Meta("Model", it) }
                // The rung the *next* turn would run on, as the desk resolves it — absent on a
                // chat running at the default effort, which is most of them, and on any machine
                // whose plugin predates the field.
                row.effort?.takeIf { it.isNotBlank() }
                    ?.let { Meta("Effort", it.replaceFirstChar(Char::uppercase)) }
                row.accountLabel?.takeIf { it.isNotBlank() }?.let { Meta("Account", it) }
                Meta("Messages", row.messageCount.toString())
                row.contextPct?.let { Meta("Context", "$it%") }
                Meta("Cost", formatCost(row.costUsd, row.costKnown))
                Meta("Last activity", Times.clock(row.lastActivityMs, generatedAtMs))
            }
        }
    }
}

/** The wire verb behind a sheet row; only the four organize actions reach here. */
internal fun requestFor(action: RowAction): String = when (action) {
    RowAction.PIN -> MobileSessionActionRequest.PIN
    RowAction.UNPIN -> MobileSessionActionRequest.UNPIN
    RowAction.DONE -> MobileSessionActionRequest.DONE
    RowAction.REOPEN -> MobileSessionActionRequest.REOPEN
    else -> MobileSessionActionRequest.RENAME
}

private fun organizeIcon(action: RowAction): ImageVector = when (action) {
    RowAction.PIN, RowAction.UNPIN -> Icons.Filled.Star
    RowAction.DONE -> Icons.Filled.Done
    else -> Icons.Filled.Refresh
}

/**
 * A text field for the new name, filled with the current one and selected, so typing replaces it.
 * Blank restores a Claude chat's first prompt, as at the desk; Codex refuses an unnamed thread,
 * so there Rename waits for a name and the hint is absent. Sized by hand like `ScheduleEditorWindow`: a dialog at the
 * platform's default width holding a text field never goes idle under Robolectric, so it could not
 * be tested.
 */
@Composable
internal fun RenameDialog(current: String, codex: Boolean, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val canRename = !codex || text.text.isNotBlank()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text("Rename chat", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.text.length <= MobileSessionActionRequest.MAX_TITLE) text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canRename) onRename(text.text) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
                )
                if (!codex) {
                    Text(
                        "Leave empty to use the first prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onRename(text.text) }, enabled = canRename) { Text("Rename") }
                }
            }
        }
    }
}

/**
 * The machine's own account of what deleting will do — which copies go where, what is left alone,
 * which scheduled prompts are cancelled — with the destructive button named for the act. Nothing
 * is deleted until the reader presses it, and then only if the chat still matches this preview.
 */
@Composable
internal fun DeleteDialog(preview: MobileSessionDeletePreview, onDismiss: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text(
                    if (preview.movesToTrash) "Move chat to Trash?" else "Delete chat permanently?",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    deleteConsequences(preview),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onDelete) {
                        Text(
                            if (preview.movesToTrash) "Move to Trash" else "Delete",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The desk's "Continue on <account>": the accounts this chat could move to, each with the reset time
 * of a limit it has reached, since a limit is why a reader usually asks. Picking one copies the chat
 * onto it and opens the copy; the chat stays on its own account.
 */
@Composable
internal fun HandoffDialog(
    title: String,
    accounts: List<MobileScheduleAccountOption>,
    nowMs: Long,
    onDismiss: () -> Unit,
    onPick: (MobileScheduleAccountOption) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    "Continue on another account",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    "Copies \u201c${title.ifBlank { "this chat" }}\u201d and its file history to the account you pick and opens the copy, with a message ready to send. This chat stays where it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(accounts, key = { it.id }) { account ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClickLabel = "Continue on ${account.label}") { onPick(account) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(account.label, style = MaterialTheme.typography.bodyLarge)
                            account.resetAtMs?.takeIf { it > nowMs }?.let {
                                Text(
                                    (if (account.weeklyLimit) "Weekly limit reached" else "Limit reached") + " \u00b7 resets ${Times.clock(it, nowMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The desk's "New chat from before…" picker: the reader's own messages, newest first because a
 * phone forks from a recent turn far more often than from the first. Each row names the message
 * and when it was sent; the fork keeps everything before it and puts it back in the new
 * composer. Messages the machine left off the list are counted, not silently missing.
 */
@Composable
internal fun ForkDialog(points: MobileSessionForkPoints, onDismiss: () -> Unit, onPick: (MobileForkPoint) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    "New chat from before…",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    "The new chat keeps everything before the message you pick, and the message waits in its composer. This chat does not change.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(points.points.asReversed(), key = { it.id }) { point ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClickLabel = "Fork from here") { onPick(point) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
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
                                point.atMs?.let {
                                    Text(
                                        Times.clock(it, System.currentTimeMillis()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (points.omitted > 0) {
                        item(key = "omitted") {
                            Text(
                                if (points.omitted == 1) "1 older message is not listed here — fork from it in the IDE."
                                else "${points.omitted} older messages are not listed here — fork from them in the IDE.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** The desk's confirmation sentences, told from the phone: the Trash is the machine's, not this phone's. */
internal fun deleteConsequences(preview: MobileSessionDeletePreview): String = buildString {
    val title = preview.title.ifBlank { "this chat" }
    if (preview.movesToTrash) {
        append(
            if (preview.transcriptCopies > 1) "All ${preview.transcriptCopies} copies of \u201c$title\u201d move"
            else "\u201c$title\u201d moves",
        )
        append(" to the Trash on the machine, where it can be restored. ")
        append("Project files, code changes and prompt history do not change.")
    } else {
        append("Codex deletes its saved session for \u201c$title\u201d. This cannot be undone. ")
        append("Project files and code changes do not change.")
    }
    when (preview.scheduledPrompts) {
        0 -> Unit
        1 -> append("\n\nThe scheduled prompt for this chat is also canceled.")
        else -> append("\n\nAll ${preview.scheduledPrompts} scheduled prompts for this chat are also canceled.")
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) =
    SheetAction(rememberVectorPainter(icon), label, onClick)

@Composable
private fun SheetAction(icon: Painter, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}
