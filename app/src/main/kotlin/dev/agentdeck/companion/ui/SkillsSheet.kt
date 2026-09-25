package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileSkillRow
import com.github.claudeagents.core.mobile.MobileSkillsList
import com.github.claudeagents.core.mobile.MobileSkillsProject

/**
 * Settings › Resources › "Skills and agents": the skills, legacy commands and subagents the desk's
 * Settings › Skills and Settings › Subagents list for an open project, read-only.
 *
 * Read again each time the sheet opens and whenever another project is picked, so a skill written on the
 * desk is there. The machine sends a description (name, the first lines of what it does, where it comes
 * from and why it is not simply on), never a file, so what is shown is all there is. The project chips
 * appear only when more than one project is open. Turning a skill off or editing one stays on the desk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsSheet(onLoad: suspend (String?) -> MobileSkillsList?, onDismiss: () -> Unit) {
    var project by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<MobileSkillsList?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Kept across a load that fails, so the chips survive a tap on a project the machine did not answer for.
    var known by remember { mutableStateOf(emptyList<MobileSkillsProject>()) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(project) {
        loading = true
        loaded = load(project)
        loaded?.projects?.takeIf { it.size > 1 }?.let { known = it }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Skills and agents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val result = loaded
            if (known.size > 1) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    known.forEach { open ->
                        FilterChip(selected = open.path == (project ?: result?.project), onClick = { project = open.path }, label = { Text(open.name) })
                    }
                }
            }
            when {
                loading -> Note("Reading the machine's skills and agents…")
                result == null -> Note("The machine did not answer. Close this and try again.")
                else -> {
                    result.notes.forEach { Note(it) }
                    if (result.rows.isEmpty() && result.notes.isEmpty()) Note("This project has no skills, commands or subagents.")
                    val sections = skillSections(result.rows)
                    LazyColumn(Modifier.heightIn(max = 560.dp).testTag("skills-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sections.forEach { (title, rows) ->
                            item(key = "h/$title") {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            // The index keeps two same-named rows (one shadowing the other) from sharing a key, which would crash the list.
                            itemsIndexed(rows, key = { index, row -> "${row.kind}/$index/${row.name}" }) { _, row -> SkillRow(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(row: MobileSkillRow) {
    Column(Modifier.fillMaxWidth().testTag("skills-row").padding(top = 8.dp)) {
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        Detail(skillSourceLine(row))
        if (row.description.isNotEmpty()) Detail(row.description, maxLines = 3)
        if (row.detail.isNotEmpty()) Detail(row.detail)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Detail(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** "Project · Not active (shadowed)": where it comes from and, when it is not simply on, why. */
internal fun skillSourceLine(row: MobileSkillRow): String =
    listOf(row.source, row.status).filter { it.isNotEmpty() }.joinToString(" · ")

/** The sections in the desk's order — skills, commands, then subagents — each headed by its count; an empty one is left out. */
internal fun skillSections(rows: List<MobileSkillRow>): List<Pair<String, List<MobileSkillRow>>> = listOf(
    "skill" to "Skills",
    "command" to "Commands",
    "agent" to "Subagents",
).mapNotNull { (kind, title) ->
    rows.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { "$title (${it.size})" to it }
}
