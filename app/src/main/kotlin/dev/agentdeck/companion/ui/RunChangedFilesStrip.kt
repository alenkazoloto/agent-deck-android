package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileRunChangedFile
import com.github.claudeagents.core.mobile.MobileRunChangedFiles

/**
 * The desk's changed-files strip above the composer while a run works — read-only on the phone: the
 * desk's row opens a file in the IDE editor, which a phone has none of, so a name here is display
 * only. Drawn only where Settings › Machine's "Changed files while a run works" is on and the
 * machine's page carries a run still going; the Done line's own summary, once it ends, has no strip
 * because [dev.agentdeck.companion.ui.ConversationScreen]'s turn text already carries it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunChangedFilesStrip(files: List<MobileRunChangedFile>) {
    if (files.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Changed so far:",
            Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "${files.size} file${if (files.size == 1) "" else "s"} changed so far"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        files.take(MobileRunChangedFiles.MAX_NAMED).forEach { file ->
            Text(
                fileLine(file),
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = fileDescription(file) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MobileRunChangedFiles.overflow(files)?.let {
            Text(it, Modifier.heightIn(min = 48.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Trimmed to the base name for the width budget, as the desk's own row trims it; the tally follows in the same label. */
private fun fileLine(file: MobileRunChangedFile): String {
    val name = file.path.substringAfterLast('/')
    return if (file.label != null) "$name ${file.label}" else name
}

private fun fileDescription(file: MobileRunChangedFile): String =
    if (file.label != null) "${file.path}, ${file.label}" else file.path
