package dev.agentdeck.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileWorkingDiff
import com.github.claudeagents.core.mobile.MobileWorkingDiffFile
import dev.agentdeck.companion.data.WorkingDiffFlow
import dev.agentdeck.companion.data.WorkingDiffSheet

/**
 * A Codex chat's `/diff`, as the desk's dialog: the repository's uncommitted files with their
 * `+n −n` and whether git tracks them, and a file's patch on tap. A question, not an action —
 * nothing here stages or writes, so Close is the only button.
 */
@Composable
fun WorkingDiffDialog(
    sheet: WorkingDiffSheet,
    onDismiss: () -> Unit,
    onOpenFile: (String) -> Unit,
    onCloseFile: () -> Unit,
    onRefresh: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Back from a patch returns to the list, as the ArrowBack does; from the list it closes.
        BackHandler(enabled = sheet.openPath != null, onBack = onCloseFile)
        Surface(
            Modifier.padding(horizontal = 16.dp, vertical = 40.dp).widthIn(max = 720.dp).fillMaxWidth().testTag("working-diff-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                val open = sheet.openPath
                if (open == null) {
                    Text(MobileWorkingDiff.TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                } else {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeckIconButton(label = "Back to the changed files", icon = Icons.Filled.ArrowBack, onClick = onCloseFile)
                        Text(
                            open,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("working-diff-open-path"),
                        )
                    }
                }
                val diff = sheet.diff
                Column(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                    when {
                        diff == null && sheet.error == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
                        diff == null -> Unit
                        open != null -> Patch(sheet.openFile, sheet.loadingFile)
                        diff.outcome != MobileWorkingDiff.CHANGES -> Note(diff.message.orEmpty(), Modifier.testTag("working-diff-message"))
                        else -> Files(diff, onOpenFile)
                    }
                }
                sheet.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("working-diff-error"),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val retry = if (open == null) sheet.error != null || diff?.outcome == MobileWorkingDiff.SLOW
                    else sheet.openFile == null && !sheet.loadingFile && sheet.error != null && sheet.error != WorkingDiffFlow.GONE
                    if (retry) TextButton(onClick = onRefresh, modifier = Modifier.testTag("working-diff-retry")) { Text("Try again") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Files(diff: MobileWorkingDiff, onOpenFile: (String) -> Unit) {
    LazyColumn(Modifier.testTag("working-diff-files")) {
        item(key = "summary") {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(diff.summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("working-diff-summary"))
                diff.root?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // By place: the desk's parser keeps two unnamed sections as two "(unknown)" rows.
        itemsIndexed(diff.files, key = { i, _ -> "file-$i" }) { _, file -> FileRow(file) { onOpenFile(file.path) } }
        if (diff.filesNotShown > 0) item(key = "more") {
            Note(if (diff.filesNotShown == 1) "1 more file is in the IDE's /diff." else "${diff.filesNotShown} more files are in the IDE's /diff.")
        }
        item(key = "boundary") { Note(diff.boundary) }
    }
}

@Composable
private fun FileRow(file: MobileWorkingDiffFile, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Show the patch", onClick = onOpen)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("working-diff-file"),
    ) {
        Text(
            file.path,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${file.counts} · ${file.stateLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun Patch(file: MobileWorkingDiffFile?, loading: Boolean) {
    val patch = file?.patch
    if (patch == null) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
        return
    }
    val lines = remember(patch) { patch.trimEnd('\n').split('\n') }
    // One horizontal scroll around every line, so a wide patch moves as one page; per-line
    // scrollables sharing a state reset it whenever a short line is measured.
    BoxWithConstraints(Modifier.testTag("working-diff-patch")) {
        val page = maxWidth
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "${file.counts} · ${file.stateLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Column(Modifier.horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max).widthIn(min = page)) {
                lines.forEach { line ->
                    val tint = when {
                        line.startsWith("+++") || line.startsWith("---") -> Color.Transparent
                        line.startsWith("+") -> ADDED.copy(alpha = 0.18f)
                        line.startsWith("-") -> REMOVED.copy(alpha = 0.18f)
                        else -> Color.Transparent
                    }
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (line.startsWith("diff --git") || line.startsWith("@@")) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth().background(tint).padding(horizontal = 12.dp),
                    )
                }
            }
            if (file.truncated) Note("The patch is cut short here; the IDE's /diff shows the rest.")
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

private val ADDED = Color(0xFF2E7D32)
private val REMOVED = Color(0xFFC62828)
