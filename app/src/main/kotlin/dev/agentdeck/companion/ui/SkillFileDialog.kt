package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.agentdeck.companion.data.SkillFileSheet

/** Everything the dialog can ask of its flow; one bundle so a test drives it without a view model. */
internal class SkillFileActions(
    val onEdit: (String) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onKeepMine: () -> Unit,
    val onLoadTheirs: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Resources › Skills, agents and plugins › a skill's or command's "Edit file": the desk's "Open SKILL.md" as text on one full-screen
 * dialog (`/v1/skill-file`), with the memory editor's Save, Discard and conflict card. Back closes it and never discards typing — the
 * edit is kept until the machine takes it or the reader presses Discard. A phone the owner has not allowed to save shows the file
 * read-only and says how to change that, rather than a Save that fails.
 */
@Composable
internal fun SkillFileDialog(sheet: SkillFileSheet, actions: SkillFileActions) {
    Dialog(onDismissRequest = actions.onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("skill-file-dialog"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("skill-file-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text("Back", modifier = Modifier.padding(start = 8.dp))
                    }
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(sheet.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sheet.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val open = sheet.open
                when {
                    open != null -> FileEditor(
                        open = open,
                        busy = sheet.busy,
                        tag = "skill-file",
                        readOnly = SKILL_FILE_READ_ONLY,
                        onEdit = actions.onEdit,
                        onSave = actions.onSave,
                        onDiscard = actions.onDiscard,
                        onKeepMine = actions.onKeepMine,
                        onLoadTheirs = actions.onLoadTheirs,
                    )
                    sheet.error != null -> FileNotice("skill-file", sheet.error, error = true)
                    else -> FileNotice("skill-file", "Reading the skill's file…")
                }
            }
        }
    }
}

/** Where the owner turns saving on for this phone; the machine's refusal names the same place. */
internal const val SKILL_FILE_READ_ONLY =
    "Read only: saving skill files from this phone is off. Turn it on for this phone in the IDE, under Settings › Connections › Mobile › Devices."
