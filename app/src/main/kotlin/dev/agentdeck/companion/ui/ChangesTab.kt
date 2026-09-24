package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileReviewNote
import com.github.claudeagents.core.mobile.MobileReviewScope
import com.github.claudeagents.core.mobile.MobileToolResult
import dev.agentdeck.companion.data.NoteLine
import dev.agentdeck.companion.data.SyntaxLite

/**
 * What the agent changed, read from the couch.
 *
 * Two depths and no third: a checklist of files, and one file's diff. Per-hunk undo and editing
 * are absent, because a mis-tap on a phone is unforgivable (`PLAN-MOBILE-COMPANION` G5). The
 * writes are the tick, reversible by the same control that set it, and "Commit…" and "Revert…"
 * (M4), which only land after their own sheet names every file and is confirmed.
 *
 * The diff is rendered by the first character of each line, which is what the wire carries: the
 * machine decided what a hunk is, so this file never re-derives it and the phone and the panel
 * beside it cannot disagree about what changed.
 */
@Composable
fun ChangesTab(
    review: MobileReviewList?,
    loading: Boolean,
    error: String?,
    marking: Boolean,
    openPath: String?,
    diff: MobileReviewFileDiff?,
    diffLoading: Boolean,
    diffError: String?,
    softWrap: Boolean,
    lineNumbers: Boolean,
    /**
     * A file's path, the request whose own change of it to show (null for the whole session), and
     * the revision to hold it against (null for the session's start).
     */
    onOpenFile: (path: String, request: String?, base: String?) -> Unit,
    onCloseFile: () -> Unit,
    onMark: (paths: List<String>, reviewed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The machine serves `/v1/review/{key}/commit`; null hides "Commit…". */
    onCommit: (() -> Unit)? = null,
    /** The machine serves `/v1/review/{key}/revert`; null hides "Revert…". */
    onRevert: (() -> Unit)? = null,
    /** The machine serves `/v1/review/{key}/notes`; null hides review notes. */
    notes: ReviewNotesUi? = null,
    /** The machine takes `&base=` ([MobileProtocol.Capability.REVIEW_DIFF_BASE]); false hides the Base. */
    diffBases: Boolean = false,
    /**
     * Writes the desk's "Ignore whitespace when comparing files"
     * ([MobileProtocol.Capability.REVIEW_IGNORE_WHITESPACE]); null hides the diff's switch.
     */
    onIgnoreWhitespace: ((Boolean) -> Unit)? = null,
) {
    // The desk's "Group by request" scope: a request's uuid, [OTHER_CHANGES_SCOPE], or null for
    // the whole session. Kept per conversation across a diff and back; a scope a refresh no
    // longer lists reads as the whole session rather than as an empty list.
    var scopeId by rememberSaveable(review?.key) { mutableStateOf<String?>(null) }
    val scope = review?.requests?.firstOrNull { scopeId != null && (it.request ?: OTHER_CHANGES_SCOPE) == scopeId }
    // The desk side's Base, kept per conversation like the scope. It belongs to the whole-session
    // diff: a request's diff has its own two sides, so a scoped list neither shows nor sends it.
    var base by rememberSaveable(review?.key) { mutableStateOf<String?>(null) }
    var typingBase by rememberSaveable(review?.key) { mutableStateOf(false) }
    val baseUi = if (diffBases && scope == null) {
        BaseUi(base, typingBase, { typingBase = true }) { base = it; typingBase = false }
    } else {
        null
    }
    Column(modifier.fillMaxSize().testTag("conversation-changes")) {
        if (loading || diffLoading || marking) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            openPath != null -> FileDiff(
                path = openPath,
                diff = diff,
                error = diffError,
                loading = diffLoading,
                softWrap = softWrap,
                lineNumbers = lineNumbers,
                markable = review?.writerNotice == null,
                notes = notes,
                onIgnoreWhitespace = onIgnoreWhitespace,
                onBack = onCloseFile,
                onMark = { reviewed -> onMark(listOf(openPath), reviewed) },
            )
            error != null -> Notice(error)
            review == null && loading -> Unit
            review == null -> Notice("Changes could not be read from this machine.")
            review.files.isEmpty() -> Notice("This conversation changed no files.")
            else -> FileList(
                review, scope, { scopeId = it }, { path -> onOpenFile(path, scope?.request, baseUi?.base) }, onCommit, onRevert, notes, baseUi,
            ) { paths, reviewed -> onMark(paths, reviewed) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FileList(
    review: MobileReviewList,
    scope: MobileReviewScope?,
    onScope: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onCommit: (() -> Unit)?,
    onRevert: (() -> Unit)?,
    notes: ReviewNotesUi?,
    baseUi: BaseUi?,
    onMark: (paths: List<String>, reviewed: Boolean) -> Unit,
) {
    // A scope's own files; "Mark all" follows the scope, as the desk's per-request select-all does.
    val files = scope?.paths?.toSet()?.let { inScope -> review.files.filter { it.path in inScope } } ?: review.files
    val allReviewed = files.isNotEmpty() && files.all { it.reviewed }
    if (review.requests.isNotEmpty() || baseUi != null) {
        // Wraps rather than squeezes: at 200% text the two selectors do not share one line.
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (review.requests.isNotEmpty()) {
                Selector(
                    options = listOf(SelectorOption("Whole session", null as String?)) + review.requests.map {
                        SelectorOption(scopeOption(it), it.request ?: OTHER_CHANGES_SCOPE)
                    },
                    selected = scope?.let { it.request ?: OTHER_CHANGES_SCOPE },
                    onSelect = onScope,
                    modifier = Modifier.testTag("changes-scope"),
                )
            }
            baseUi?.let { DiffBaseSelector(it.base, it.onOther, it.onBase) }
        }
    }
    if (baseUi?.typing == true) {
        DiffBaseField(baseUi.base, baseUi.onBase, Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(scope?.label ?: changesSummary(review), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (review.writerNotice == null) {
            TextButton(onClick = { onMark(if (scope == null) emptyList() else files.map { it.path }, !allReviewed) }) {
                Text(if (allReviewed) "Clear all" else "Mark all reviewed")
            }
        }
    }
    // Its own row: at 200% text the summary and two buttons do not share one line.
    if (review.writerNotice == null && (onCommit != null || onRevert != null)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            onRevert?.let { TextButton(onClick = it, modifier = Modifier.testTag("changes-revert")) { Text("Revert…") } }
            onCommit?.let { TextButton(onClick = it, modifier = Modifier.testTag("changes-commit")) { Text("Commit…") } }
        }
    }
    review.writerNotice?.let { Notice(it) }
    notes?.let { NotesRow(it) }
    HorizontalDivider()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(files, key = { it.path }) { file ->
            FileRow(file, onOpen = { onOpenFile(file.path) }, markable = review.writerNotice == null) {
                onMark(listOf(file.path), !file.reviewed)
            }
        }
    }
}

@Composable
private fun FileRow(file: MobileReviewFile, onOpen: () -> Unit, markable: Boolean, onMark: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val parent = file.path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                Text(
                    parent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            Text(
                file.note ?: fileCounts(file),
                style = MaterialTheme.typography.bodySmall,
                color = if (file.note != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (markable) {
            Checkbox(
                checked = file.reviewed,
                onCheckedChange = { onMark() },
                modifier = Modifier.semantics {
                    contentDescription = if (file.reviewed) {
                        "Reviewed: ${file.path}. Tap to clear."
                    } else {
                        "Mark reviewed: ${file.path}"
                    }
                },
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun ColumnScope.FileDiff(
    path: String,
    diff: MobileReviewFileDiff?,
    error: String?,
    loading: Boolean,
    softWrap: Boolean,
    lineNumbers: Boolean,
    markable: Boolean,
    notes: ReviewNotesUi?,
    onIgnoreWhitespace: ((Boolean) -> Unit)?,
    onBack: () -> Unit,
    onMark: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeckIconButton(
            label = "Back to the changed files",
            icon = Icons.Filled.ArrowBack,
            onClick = onBack,
        )
        Text(
            path.substringAfterLast('/'),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        if (markable && diff != null) {
            TextButton(onClick = { onMark(!diff.file.reviewed) }) {
                Text(if (diff.file.reviewed) "Reviewed" else "Mark reviewed")
            }
        }
    }
    HorizontalDivider()
    diff?.beforeTitle?.let { before ->
        Text(
            "$before → ${diff.afterTitle.orEmpty()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("changes-diff-sides"),
        )
    }
    if (diff != null && onIgnoreWhitespace != null) {
        // The desk's own value, as the diff was cut under it — the diff window's gear, not a phone copy.
        FilterChip(
            selected = diff.ignoreWhitespace,
            onClick = { onIgnoreWhitespace(!diff.ignoreWhitespace) },
            label = { Text("Ignore whitespace") },
            // A tick as well as the fill: "on" must not be told by colour alone.
            leadingIcon = if (diff.ignoreWhitespace) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else {
                null
            },
            enabled = !loading,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).heightIn(min = 48.dp).testTag("changes-ignore-whitespace"),
        )
    }
    diff?.scopeNote?.let { Notice(it) }
    when {
        error != null -> Notice(error)
        diff == null -> if (!loading) Notice("This file's diff could not be read.") else Unit
        diff.hunks.isEmpty() && diff.file.note == null && diff.ignoreWhitespace -> Notice("No changes to show with whitespace ignored.")
        diff.hunks.isEmpty() -> Notice(diff.file.note ?: "No line changes to show for this file.")
        // A request's sides are not the file notes anchor to (the desk keeps request-diff notes on
        // their own revisions), so a request diff offers none rather than misplacing them.
        else -> DiffLines(diff, softWrap, lineNumbers, notes.takeIf { diff.beforeTitle == null })
    }
}

@Composable
private fun ColumnScope.DiffLines(diff: MobileReviewFileDiff, softWrap: Boolean, lineNumbers: Boolean, notes: ReviewNotesUi?) {
    val scroll = rememberScrollState()
    // The file's own name is the language here, where a fence has an info string. One line at a
    // time, so a hunk that opens a string or a block comment and never closes it inside the
    // hunk cannot tint the rest of the file — the same bound `SyntaxLite` puts on a fence.
    val rules = remember(diff.file.path) { SyntaxLite.rulesForPath(diff.file.path) }
    val hunks = remember(diff) { diff.hunks.map(::numbered) }
    val fileNotes = notes?.notes.orEmpty().filter { it.path == diff.file.path && !it.outdated }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().testTag("changes-diff"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        hunks.forEachIndexed { index, rows ->
            val hunk = diff.hunks[index]
            item(key = "hunk-$index") {
                Text(
                    "@@ ${hunk.beforeStart} → ${hunk.afterStart} @@",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            rows.forEachIndexed { line, row ->
                item(key = "hunk-$index-$line") {
                    val add = notes?.let { ui ->
                        {
                            val side = row.noteSide
                            ui.onAdd(diff.file.path, side, row.noteLine(side), rows.drop(line + 1).mapNotNull { it.lineOn(side) })
                        }
                    }
                    DiffLine(row.text, row.number, softWrap, lineNumbers, scroll, rules, add)
                }
                fileNotes.filter { row.ends(it) }.forEach { note ->
                    item(key = "note-${note.id}-$index-$line") { NoteBanner(note) { notes?.onOpenNote?.invoke(note) } }
                }
            }
        }
        if (diff.omittedBytes > 0) {
            item(key = "omitted") {
                Notice("… ${MobileToolResult.humanBytes(diff.omittedBytes)} of this diff omitted. Open it in the IDE to read the rest.")
            }
        }
    }
}

/**
 * A unified diff line with its number on each side it exists on: a removal is only in the
 * before, an addition only in the after, context in both. Numbering walks the *before* side for
 * context and removals and the *after* side for additions, which is what a gutter beside a
 * unified diff has to show.
 */
private data class DiffRow(val text: String, val before: Int?, val after: Int?) {
    /** What the gutter shows: the before side unless the line is an addition. */
    val number: Int get() = before ?: after ?: 0

    /** The desk's unified diff anchors a line to the right side when it has one, as here. */
    val noteSide: String get() = if (after != null) MobileReviewNote.CURRENT else MobileReviewNote.BASELINE

    fun lineOn(side: String): NoteLine? =
        (if (side == MobileReviewNote.CURRENT) after else before)?.let { NoteLine(it, text.drop(1)) }

    fun noteLine(side: String): NoteLine = lineOn(side) ?: NoteLine(number, text.drop(1))

    fun ends(note: MobileReviewNote): Boolean =
        if (note.side == MobileReviewNote.CURRENT) after == note.endLine else before == note.endLine
}

private fun numbered(hunk: com.github.claudeagents.core.mobile.MobileReviewHunk): List<DiffRow> {
    var beforeLine = hunk.beforeStart
    var afterLine = hunk.afterStart
    return hunk.lines.map { text ->
        when (text.firstOrNull()) {
            '+' -> DiffRow(text, null, afterLine++)
            '-' -> DiffRow(text, beforeLine++, null)
            else -> DiffRow(text, beforeLine++, afterLine++)
        }
    }
}

@Composable
private fun DiffLine(
    text: String,
    number: Int,
    softWrap: Boolean,
    lineNumbers: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
    rules: dev.agentdeck.companion.data.SyntaxRules?,
    /** Adds a review note on this line; long-press, or TalkBack's action of the same name. */
    onAddNote: (() -> Unit)? = null,
) {
    val kind = text.firstOrNull()
    val line = highlighted(text, rules)
    val tint = when (kind) {
        '+' -> ADDED.copy(alpha = 0.18f)
        '-' -> REMOVED.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val press = onAddNote?.let { add ->
        Modifier
            .pointerInput(add) { detectTapGestures(onLongPress = { add() }) }
            .semantics { customActions = listOf(CustomAccessibilityAction("Add review note") { add(); true }) }
    } ?: Modifier
    Row(Modifier.fillMaxWidth().background(tint).then(press)) {
        if (lineNumbers) {
            Text(
                number.toString().padStart(4),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp).clearAndSetSemantics { },
            )
        }
        // One shared horizontal scroll across every line, so a wide diff moves as one page
        // rather than as a stack of independently sliding strips.
        val body = Modifier.weight(1f).padding(end = 12.dp)
        Text(
            line,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            softWrap = softWrap,
            maxLines = if (softWrap) Int.MAX_VALUE else 1,
            modifier = if (softWrap) body else body.horizontalScroll(scroll),
        )
    }
}

/** "Review notes · 2 open", above the files; opens the notes sheet. */
@Composable
private fun NotesRow(notes: ReviewNotesUi) {
    val open = notes.notes.size
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = notes.onOpenList)
            .padding(horizontal = 16.dp).testTag("changes-notes"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Review notes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (open == 0) "Long-press a diff line to add" else "$open open",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A saved note under the line it ends on, as the desk's diff shows its banner. */
@Composable
private fun NoteBanner(note: MobileReviewNote, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Edit review note", onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("diff-note"),
    ) {
        Text(
            if (note.startLine == note.endLine) "Note · line ${note.startLine}" else "Note · lines ${note.startLine}–${note.endLine}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(note.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** The whole-session list's Base: the revision in force (null for session start) and its setter. */
private class BaseUi(val base: String?, val typing: Boolean, val onOther: () -> Unit, val onBase: (String?) -> Unit)

/** What the Changes tab needs of review notes; null where the machine does not serve them. */
class ReviewNotesUi(
    /** The conversation's open notes. */
    val notes: List<MobileReviewNote>,
    val onOpenList: () -> Unit,
    val onAdd: (path: String, side: String, line: NoteLine, following: List<NoteLine>) -> Unit,
    val onOpenNote: (MobileReviewNote) -> Unit,
)

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

/** The Selector's value for the "Other changes" bucket, which has no request id. */
private const val OTHER_CHANGES_SCOPE = "other-changes"

/** "2. Fix the parser", as the desk's group header numbers a request; the bucket by its name. */
fun scopeOption(scope: MobileReviewScope): String =
    if (scope.number > 0) "${scope.number}. ${scope.prompt.replace(Regex("\\s+"), " ").trim()}" else scope.prompt

/** "4 files · +120 −31" — and the sizes are dropped when a file could not be measured. */
fun changesSummary(review: MobileReviewList): String {
    val files = review.files.size
    val head = if (files == 1) "1 file" else "$files files"
    val measured = review.files.all { it.note == null }
    return if (!measured || files == 0) head else "$head · +${review.added} −${review.removed}"
}

/** "+12 −3", or the status alone where a count would be a lie. */
fun fileCounts(file: MobileReviewFile): String = when (file.status) {
    MobileReviewFile.REMOVED -> "deleted"
    MobileReviewFile.ADDED -> "new file · +${file.added}"
    else -> "+${file.added} −${file.removed}"
}

private val ADDED = Color(0xFF2E7D32)
private val REMOVED = Color(0xFFC62828)

/**
 * Messages | Changes.
 *
 * A real tab strip rather than a menu or a sheet: the two halves are peers a reviewer moves
 * between repeatedly, and the current one has to be visible without being asked for.
 */
@Composable
fun ConversationTabs(
    changesOpen: Boolean,
    changesLabel: String,
    onMessages: () -> Unit,
    onChanges: () -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = if (changesOpen) 1 else 0, modifier = Modifier.testTag("conversation-tabs")) {
        Tab(
            selected = !changesOpen,
            onClick = onMessages,
            text = { Text("Messages") },
        )
        Tab(
            selected = changesOpen,
            onClick = onChanges,
            text = { Text(changesLabel) },
        )
    }
}

/** "Changes", or "Changes (4)" once the machine has said how many files there are. */
fun changesTabLabel(review: MobileReviewList?): String =
    if (review == null || review.files.isEmpty()) "Changes" else "Changes (${review.files.size})"
