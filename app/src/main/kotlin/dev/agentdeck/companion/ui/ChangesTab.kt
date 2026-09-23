package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileToolResult
import dev.agentdeck.companion.data.SyntaxLite

/**
 * What the agent changed, read from the couch.
 *
 * Two depths and no third: a checklist of files, and one file's diff. Revert, per-hunk undo and
 * editing are absent, because a mis-tap on a phone is unforgivable and none of those is undoable
 * from here (`PLAN-MOBILE-COMPANION` G5). The writes are the tick, reversible by the same control
 * that set it, and "Commit…" (M4), which only lands after its own sheet is confirmed.
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
    onOpenFile: (String) -> Unit,
    onCloseFile: () -> Unit,
    onMark: (paths: List<String>, reviewed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The machine serves `/v1/review/{key}/commit`; null hides "Commit…". */
    onCommit: (() -> Unit)? = null,
) {
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
                onBack = onCloseFile,
                onMark = { reviewed -> onMark(listOf(openPath), reviewed) },
            )
            error != null -> Notice(error)
            review == null && loading -> Unit
            review == null -> Notice("Changes could not be read from this machine.")
            review.files.isEmpty() -> Notice("This conversation changed no files.")
            else -> FileList(review, onOpenFile, onCommit) { paths, reviewed -> onMark(paths, reviewed) }
        }
    }
}

@Composable
private fun ColumnScope.FileList(
    review: MobileReviewList,
    onOpenFile: (String) -> Unit,
    onCommit: (() -> Unit)?,
    onMark: (paths: List<String>, reviewed: Boolean) -> Unit,
) {
    val allReviewed = review.files.isNotEmpty() && review.files.all { it.reviewed }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(changesSummary(review), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (review.writerNotice == null) {
            TextButton(onClick = { onMark(emptyList(), !allReviewed) }) {
                Text(if (allReviewed) "Clear all" else "Mark all reviewed")
            }
        }
    }
    // Its own row: at 200% text the summary and two buttons do not share one line.
    if (review.writerNotice == null && onCommit != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCommit, modifier = Modifier.testTag("changes-commit")) { Text("Commit…") }
        }
    }
    review.writerNotice?.let { Notice(it) }
    HorizontalDivider()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(review.files, key = { it.path }) { file ->
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
    when {
        error != null -> Notice(error)
        diff == null -> if (!loading) Notice("This file's diff could not be read.") else Unit
        diff.hunks.isEmpty() -> Notice(diff.file.note ?: "No line changes to show for this file.")
        else -> DiffLines(diff, softWrap, lineNumbers)
    }
}

@Composable
private fun ColumnScope.DiffLines(diff: MobileReviewFileDiff, softWrap: Boolean, lineNumbers: Boolean) {
    val scroll = rememberScrollState()
    // The file's own name is the language here, where a fence has an info string. One line at a
    // time, so a hunk that opens a string or a block comment and never closes it inside the
    // hunk cannot tint the rest of the file — the same bound `SyntaxLite` puts on a fence.
    val rules = remember(diff.file.path) { SyntaxLite.rulesForPath(diff.file.path) }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().testTag("changes-diff"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        diff.hunks.forEachIndexed { index, hunk ->
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
            // Numbering walks the *before* side for context and removals and the *after* side
            // for additions, which is what a gutter beside a unified diff has to show.
            var beforeLine = hunk.beforeStart
            var afterLine = hunk.afterStart
            hunk.lines.forEachIndexed { line, text ->
                val number = when (text.firstOrNull()) {
                    '+' -> afterLine++
                    '-' -> beforeLine++
                    else -> { afterLine++; beforeLine++ }
                }
                item(key = "hunk-$index-$line") {
                    DiffLine(text, number, softWrap, lineNumbers, scroll, rules)
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

@Composable
private fun DiffLine(
    text: String,
    number: Int,
    softWrap: Boolean,
    lineNumbers: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
    rules: dev.agentdeck.companion.data.SyntaxRules?,
) {
    val kind = text.firstOrNull()
    val line = highlighted(text, rules)
    val tint = when (kind) {
        '+' -> ADDED.copy(alpha = 0.18f)
        '-' -> REMOVED.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(tint)) {
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

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

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
