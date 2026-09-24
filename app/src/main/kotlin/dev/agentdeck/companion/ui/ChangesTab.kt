package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.stateDescription
import com.github.claudeagents.core.mobile.MobileReviewView
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
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
import dev.agentdeck.companion.R
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
    /**
     * The machine serves `/v1/review/{key}/commit`; null hides "Commit…". Called with the chosen
     * request's files, or null for the whole session.
     */
    onCommit: ((scoped: Set<String>?) -> Unit)? = null,
    /** The machine serves `/v1/review/{key}/revert`; null hides "Revert…". Called as [onCommit]. */
    onRevert: ((scoped: Set<String>?) -> Unit)? = null,
    /** The machine serves `/v1/review/{key}/notes`; null hides review notes. */
    notes: ReviewNotesUi? = null,
    /** The machine takes `&base=` ([MobileProtocol.Capability.REVIEW_DIFF_BASE]); false hides the Base. */
    diffBases: Boolean = false,
    /**
     * Writes the desk's "Ignore whitespace when comparing files"
     * ([MobileProtocol.Capability.REVIEW_IGNORE_WHITESPACE]); null hides the diff's switch.
     */
    onIgnoreWhitespace: ((Boolean) -> Unit)? = null,
    /**
     * Writes the desk review's "Sort by" ([MobileProtocol.Capability.REVIEW_SORT]); null hides it.
     * The machine orders the list, so the phone never ranks files itself.
     */
    onSort: ((String) -> Unit)? = null,
    /**
     * Writes the desk checklist's "Group by directory" or "Show file paths"
     * ([MobileProtocol.Capability.REVIEW_VIEW_OPTIONS]); null hides the view options.
     */
    onViewOptions: ((MobileReviewView) -> Unit)? = null,
    /** The scope the tab opens on before the reader picks one; only a screenshot fixture sets it. */
    initialScope: String? = null,
) {
    // The desk's "Group by request" scope: a request's uuid, [OTHER_CHANGES_SCOPE], or null for
    // the whole session. Kept per conversation across a diff and back; a scope a refresh no
    // longer lists reads as the whole session rather than as an empty list.
    var scopeId by rememberSaveable(review?.key) { mutableStateOf(initialScope) }
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
    val openInScope = { path: String -> onOpenFile(path, scope?.request, baseUi?.base) }
    // Folded directory headings, as the desk list keeps them: per conversation, across refreshes.
    var collapsed by rememberSaveable(review?.key) { mutableStateOf(ArrayList<String>()) }
    val folds = DirectoryFolds(collapsed.toSet()) { collapsed = ArrayList(it) }
    Column(modifier.fillMaxSize().testTag("conversation-changes")) {
        if (loading || diffLoading || marking) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            openPath != null -> FileDiff(
                path = openPath,
                machinePath = copiedPath(openPath, review, diff),
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
                // The list the diff was opened from, in its order and scope, as the desk review
                // dialog's Previous/Next file step it.
                stepper = review?.let { scopedFiles(it, scope).map(MobileReviewFile::path) }
                    ?.takeIf { it.size > 1 }
                    ?.let { paths -> FileStepper(paths, openPath) { forward -> openInScope(steppedPath(paths, openPath, forward)) } },
            )
            error != null -> Notice(error)
            review == null && loading -> Unit
            review == null -> Notice("Changes could not be read from this machine.")
            review.files.isEmpty() -> Notice("This conversation changed no files.")
            else -> FileList(
                review, scope, { scopeId = it }, openInScope, onCommit, onRevert, notes, baseUi,
                onSort.takeIf { review.sortOrder != null && review.sortOptions.isNotEmpty() },
                onViewOptions, folds,
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
    onCommit: ((Set<String>?) -> Unit)?,
    onRevert: ((Set<String>?) -> Unit)?,
    notes: ReviewNotesUi?,
    baseUi: BaseUi?,
    onSort: ((String) -> Unit)?,
    onViewOptions: ((MobileReviewView) -> Unit)?,
    folds: DirectoryFolds,
    onMark: (paths: List<String>, reviewed: Boolean) -> Unit,
) {
    // A scope's own files; "Mark all" follows the scope, as the desk's per-request select-all does.
    val files = scopedFiles(review, scope)
    val allReviewed = files.isNotEmpty() && files.all { it.reviewed }
    if (review.requests.isNotEmpty() || baseUi != null || onSort != null) {
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
            if (onSort != null) {
                // The desk combo's own choices and labels; the list arrives already in the order.
                Selector(
                    options = review.sortOptions.map { SelectorOption(it.label, it.id) },
                    selected = review.sortOrder.orEmpty(),
                    onSelect = { if (it != review.sortOrder) onSort(it) },
                    prefix = "Sort:",
                    modifier = Modifier.testTag("changes-sort"),
                )
            }
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
        // At the header's end, as the desk's "⋯" sits after its Mark all.
        onViewOptions?.let { ViewOptionsMenu(review, listShape(review, scope), files, folds, it) }
    }
    // Its own row: at 200% text the summary and two buttons do not share one line.
    if (review.writerNotice == null && (onCommit != null || onRevert != null)) {
        // Under a request, the desk's file menu over that request's selected files: "Commit N
        // files…" and "Revert N files to session start…", the sheet opening with just those ticked.
        val scoped = scope?.let { files.map(MobileReviewFile::path).toSet() }
        val count = scoped?.let { if (it.size == 1) " 1 file" else " ${it.size} files" }.orEmpty()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            onRevert?.let { TextButton(onClick = { it(scoped) }, modifier = Modifier.testTag("changes-revert")) { Text("Revert$count…") } }
            onCommit?.let { TextButton(onClick = { it(scoped) }, modifier = Modifier.testTag("changes-commit")) { Text("Commit$count…") } }
        }
    }
    review.writerNotice?.let { Notice(it) }
    notes?.let { NotesRow(it) }
    HorizontalDivider()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        val shape = listShape(review, scope)
        listEntries(files, shape.grouped, folds.collapsed).forEach { entry ->
            when (entry) {
                is ListEntry.Heading -> item(key = "dir:" + entry.directory) {
                    DirectoryHeading(entry.directory, entry.folded, entry.fileCount) { folds.toggle(entry.directory) }
                }
                is ListEntry.File -> item(key = entry.file.path) {
                    val file = entry.file
                    FileRow(file, shape.spellFolder, onOpen = { onOpenFile(file.path) }, markable = review.writerNotice == null) {
                        onMark(listOf(file.path), !file.reviewed)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: MobileReviewFile, spellFolder: Boolean, onOpen: () -> Unit, markable: Boolean, onMark: () -> Unit) {
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
            if (spellFolder && parent.isNotEmpty()) {
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
    /** What the desk diff title's "Copy path" copies. */
    machinePath: String,
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
    stepper: FileStepper?,
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
        val clipboard = LocalClipboardManager.current
        DeckIconButton(
            label = "Copy path",
            icon = ImageVector.vectorResource(R.drawable.ic_content_copy),
            onClick = { clipboard.setText(AnnotatedString(machinePath)) },
            modifier = Modifier.testTag("changes-copy-path"),
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
    // Fills the height whatever it shows, so the stepper below stays at the bottom edge.
    Column(Modifier.weight(1f).fillMaxWidth()) {
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
    stepper?.let { FileStepperRow(it) }
}

/** The directory headings the reader folded, and the write that changes them. */
private class DirectoryFolds(val collapsed: Set<String>, val set: (Set<String>) -> Unit) {
    fun toggle(directory: String) = set(if (directory in collapsed) collapsed - directory else collapsed + directory)
}

/** One row of the checklist: a directory heading of a grouped list, or a file. */
internal sealed interface ListEntry {
    data class Heading(val directory: String, val folded: Boolean, val fileCount: Int) : ListEntry
    data class File(val file: MobileReviewFile) : ListEntry
}

/**
 * [files] as the desk checklist paints them: ungrouped, the files; grouped, each directory block
 * under its heading, a folded block reduced to the heading alone. The machine sent the blocks
 * contiguous; a row without a directory reads as the project root rather than breaking a block.
 */
internal fun listEntries(files: List<MobileReviewFile>, grouped: Boolean, collapsed: Set<String>): List<ListEntry> {
    if (!grouped) return files.map { ListEntry.File(it) }
    return buildList {
        files.groupBy { it.directory.orEmpty() }.forEach { (directory, block) ->
            val folded = directory in collapsed
            add(ListEntry.Heading(directory, folded, block.size))
            if (!folded) block.forEach { add(ListEntry.File(it)) }
        }
    }
}

/** The desk heading's words: the directory, or "Project root" for files at the top. */
internal fun directoryLabel(directory: String): String = directory.ifEmpty { "Project root" }

@Composable
private fun DirectoryHeading(directory: String, folded: Boolean, fileCount: Int, onToggle: () -> Unit) {
    val label = directoryLabel(directory)
    val files = if (fileCount == 1) "1 file" else "$fileCount files"
    Row(
        Modifier.fillMaxWidth()
            .testTag("changes-dir-$directory")
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (folded) "Expand" else "Collapse", onClick = onToggle)
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics {
                contentDescription = "Directory $label, ${if (folded) "collapsed" else "expanded"}, $files"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (folded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // The desk's collapsed heading carries its count, so a folded block still says its size.
            if (folded) "$label · $fileCount" else label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * The desk checklist's view menu: its two toggles, as their effective values, and Collapse/Expand
 * all over more than one directory. "Group by directory" shows only where the desk offers it —
 * under Session order, since a ranked list is one flat block.
 */
@Composable
private fun ViewOptionsMenu(
    review: MobileReviewList,
    shape: ListShape,
    files: List<MobileReviewFile>,
    folds: DirectoryFolds,
    onViewOptions: (MobileReviewView) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val spellFolder = shape.spellFolder
    val directories = if (shape.grouped) files.map { it.directory.orEmpty() }.distinct() else emptyList()
    Box {
        DeckIconButton(
            label = "Changed-files view options",
            icon = Icons.Filled.MoreVert,
            onClick = { open = true },
            modifier = Modifier.testTag("changes-view-options"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (review.groupingAdjustable) {
                CheckedItem("Group by directory", shape.grouped, "changes-group-by-directory") {
                    open = false
                    onViewOptions(MobileReviewView(groupByDirectory = !shape.grouped))
                }
            }
            CheckedItem("Show file paths", spellFolder, "changes-show-file-paths") {
                open = false
                onViewOptions(MobileReviewView(showFilePaths = !spellFolder))
            }
            if (directories.size > 1) {
                if (directories.any { it !in folds.collapsed }) {
                    DropdownMenuItem(
                        text = { Text("Collapse all directories") },
                        onClick = { open = false; folds.set(folds.collapsed + directories) },
                        modifier = Modifier.testTag("changes-collapse-all"),
                    )
                }
                if (directories.any { it in folds.collapsed }) {
                    DropdownMenuItem(
                        text = { Text("Expand all directories") },
                        onClick = { open = false; folds.set(folds.collapsed - directories.toSet()) },
                        modifier = Modifier.testTag("changes-expand-all"),
                    )
                }
            }
        }
    }
}

/** A menu toggle whose state is a tick and the words "on"/"off" to a screen reader, not colour. */
@Composable
private fun CheckedItem(label: String, checked: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
        },
        modifier = Modifier.semantics { stateDescription = if (checked) "On" else "Off" }.testTag(tag),
    )
}

/** The open file's place in the list it was opened from, and a step to its neighbour. */
private class FileStepper(val paths: List<String>, val current: String, val onStep: (forward: Boolean) -> Unit)

@Composable
private fun FileStepperRow(stepper: FileStepper) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("changes-file-stepper"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Both stay enabled at the ends and wrap, as the desk dialog's Previous/Next file do.
        DeckIconButton("Previous file", Icons.Filled.KeyboardArrowUp, onClick = { stepper.onStep(false) })
        val index = stepper.paths.indexOf(stepper.current)
        Text(
            if (index < 0) "${stepper.paths.size} files" else "File ${index + 1} of ${stepper.paths.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        DeckIconButton("Next file", Icons.Filled.KeyboardArrowDown, onClick = { stepper.onStep(true) })
    }
}

/**
 * The open file as the desk's "Copy path" names it — the machine's absolute path. A host that
 * does not send one ([MobileReviewFile.absPath] absent) already sent the absolute path as [path]
 * or is older than the field, where the conversation's own name for the file is the best left.
 */
internal fun copiedPath(path: String, review: MobileReviewList?, diff: MobileReviewFileDiff?): String =
    diff?.file?.takeIf { it.path == path }?.absPath
        ?: review?.files?.firstOrNull { it.path == path }?.absPath
        ?: path

/** The files a scope lists: a request's own, or every changed file for the whole session. */
/** A scope's own files in the order the desk's list of that request paints them; the whole list without one. */
private fun scopedFiles(review: MobileReviewList, scope: MobileReviewScope?): List<MobileReviewFile> {
    scope ?: return review.files
    val byPath = review.files.associateBy { it.path }
    return scope.paths.mapNotNull(byPath::get)
}

/**
 * How the list on screen is shaped: a request's list by the desk's own presentation of that request,
 * whose size decides its grouping, else the whole session's. Old machines send neither path flag,
 * and their rows always spelled the folder.
 */
private data class ListShape(val grouped: Boolean, val spellFolder: Boolean)

private fun listShape(review: MobileReviewList, scope: MobileReviewScope?): ListShape =
    if (scope != null) {
        ListShape(scope.groupedByDirectory, scope.showFilePaths ?: review.showFilePaths ?: true)
    } else {
        ListShape(review.groupedByDirectory, review.showFilePaths ?: true)
    }

/**
 * The neighbour of [current] in [paths], wrapping at both ends. A file the list no longer holds
 * (a refresh dropped it) steps to the list's first file forward and its last backward.
 */
internal fun steppedPath(paths: List<String>, current: String, forward: Boolean): String {
    val index = paths.indexOf(current)
    return when {
        index < 0 -> if (forward) paths.first() else paths.last()
        forward -> paths[(index + 1) % paths.size]
        else -> paths[(index - 1 + paths.size) % paths.size]
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
